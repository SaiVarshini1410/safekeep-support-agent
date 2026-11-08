package com.safekeep.agent.approval;

import com.safekeep.agent.apps.ApplicationRegistry;
import com.safekeep.agent.apps.RegisteredApplication;
import com.safekeep.agent.apps.config.ActionConfig;
import com.safekeep.agent.apps.config.VerifyConfig;
import com.safekeep.agent.audit.AuditService;
import com.safekeep.agent.common.Json;
import com.safekeep.agent.common.NotFoundException;
import com.safekeep.agent.config.SafekeepProperties;
import com.safekeep.agent.investigation.*;
import com.safekeep.agent.security.Role;
import com.safekeep.agent.tools.ToolCallOutcome;
import com.safekeep.agent.tools.ToolGateway;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The approval gate (Spec Section 9.4): role check, single use, expiry, exact parameters, verification. */
@Service
public class ApprovalService {

    private final ProposalRepository proposals;
    private final ApplicationRegistry registry;
    private final ToolGateway gateway;
    private final InvestigationService investigations;
    private final MessageRepository messages;
    private final RecommendationRepository recommendations;
    private final AuditService audit;
    private final Json json;
    private final Clock clock;
    private final Duration ttl;
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public ApprovalService(ProposalRepository proposals, ApplicationRegistry registry, ToolGateway gateway,
                           InvestigationService investigations, MessageRepository messages,
                           RecommendationRepository recommendations, AuditService audit, Json json,
                           Clock clock, SafekeepProperties props) {
        this.proposals = proposals;
        this.registry = registry;
        this.gateway = gateway;
        this.investigations = investigations;
        this.messages = messages;
        this.recommendations = recommendations;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.ttl = Duration.ofMinutes(props.agent().proposalTtlMinutes());
    }

    public ActionProposal propose(Investigation inv, ActionConfig action, Map<String, Object> params, String reason) {
        String paramsJson = json.write(params == null ? Map.of() : new LinkedHashMap<>(params));
        Instant now = clock.instant();
        ActionProposal p = proposals.save(new ActionProposal(inv.getId(), action.name(), action.description(),
                paramsJson, hash(paramsJson), action.risk(), action.requiredRole(), reason, now, now.plus(ttl)));
        audit.record(inv.getId(), "ACTION_PROPOSED", "AGENT", action.name() + " " + paramsJson);
        investigations.publish(inv.getId(), "proposal", "Action proposed: " + action.name() + " (needs approval)");
        return p;
    }

    /** Rule AR3: new evidence or a new run makes pending proposals stale. */
    public void expirePending(Long investigationId, String why) {
        for (ActionProposal p : proposals.findByInvestigationIdAndStatus(investigationId, ProposalStatus.PROPOSED)) {
            p.finish(ProposalStatus.EXPIRED, why);
            proposals.save(p);
            audit.record(investigationId, "PROPOSAL_EXPIRED", "SYSTEM", p.getActionName() + ": " + why);
        }
    }

    public ActionProposal approve(Long investigationId, Long proposalId, String user, Role role) {
        synchronized (locks.computeIfAbsent(proposalId, k -> new Object())) {
            ActionProposal p = load(investigationId, proposalId);
            Instant now = clock.instant();
            if (p.getStatus() != ProposalStatus.PROPOSED) {
                throw new IllegalStateException("Proposal is not awaiting approval (status " + p.getStatus() + ")");
            }
            if (now.isAfter(p.getExpiresAt())) {
                p.finish(ProposalStatus.EXPIRED, "Approval window of " + ttl.toMinutes() + " minutes passed");
                proposals.save(p);
                audit.record(investigationId, "PROPOSAL_EXPIRED", user, p.getActionName());
                throw new IllegalStateException("Proposal expired; ask the Agent to investigate again");
            }
            if (!role.atLeast(p.getRequiredRole())) {
                audit.record(investigationId, "APPROVAL_DENIED", user, p.getActionName() + " requires " + p.getRequiredRole());
                throw new AccessDeniedException("Requires " + p.getRequiredRole() + " approval");
            }
            Investigation inv = investigations.get(investigationId);
            RegisteredApplication app = registry.find(inv.getApplicationId()).orElseThrow();
            ActionConfig action = app.action(p.getActionName()).orElseThrow();

            if (!hash(p.getParamsJson()).equals(p.getParamsHash())) {     // AR5
                p.decide(ProposalStatus.FAILED, user, now, null);
                p.finish(ProposalStatus.FAILED, "Integrity check failed: parameters changed after proposal");
                proposals.save(p);
                audit.record(investigationId, "INTEGRITY_FAILED", user, p.getActionName());
                return p;
            }
            if (!registry.isEnabled(app.id(), action.name())) {          // disabled after proposal
                p.decide(ProposalStatus.FAILED, user, now, null);
                p.finish(ProposalStatus.FAILED, "Not executed: action disabled by an administrator");
                proposals.save(p);
                recommendations.save(new Recommendation(investigationId, manualText(action), 1, RecommendationKind.MANUAL));
                audit.record(investigationId, "ACTION_BLOCKED", user, action.name() + " TOOL_DISABLED");
                note(investigationId, "Action " + action.name() + " was not executed because it is disabled. Manual steps were added.");
                return p;
            }

            p.decide(ProposalStatus.APPROVED, user, now, null);
            proposals.save(p);
            audit.record(investigationId, "ACTION_APPROVED", user, p.getActionName() + " " + p.getParamsJson());
            p.setStatus(ProposalStatus.EXECUTING);                        // AR2: single use
            proposals.save(p);
            investigations.publish(investigationId, "action", "Executing " + p.getActionName());

            Map<String, Object> params = json.readMap(p.getParamsJson());
            ToolCallOutcome exec = gateway.executeAction(inv, action.name(), params, user, p.getId());
            if (!exec.ok()) {
                return conclude(p, inv, ProposalStatus.FAILED, Resolution.UNRESOLVED,
                        "Action failed: " + exec.errorCode() + " " + exec.message());
            }
            return verify(p, inv, app, action);
        }
    }

    private ActionProposal verify(ActionProposal p, Investigation inv, RegisteredApplication app, ActionConfig action) {
        VerifyConfig v = action.verify();
        ToolCallOutcome check = gateway.executeRead(inv, v.tool(), new LinkedHashMap<>(v.args()), "SYSTEM");
        if (!check.ok()) {
            return conclude(p, inv, ProposalStatus.FAILED, Resolution.UNRESOLVED,
                    "Action ran but could not be verified (" + check.errorCode() + ")");
        }
        Map<String, Double> thresholds = app.config().thresholds();
        if (ConditionEvaluator.evaluate(v.condition(), check.result(), thresholds)) {
            return conclude(p, inv, ProposalStatus.SUCCEEDED, Resolution.RESOLVED,
                    "Succeeded and verified (" + check.evidenceId() + ": " + v.condition() + ")");
        }
        if (v.partialCondition() != null && ConditionEvaluator.evaluate(v.partialCondition(), check.result(), thresholds)) {
            return conclude(p, inv, ProposalStatus.SUCCEEDED, Resolution.PARTIALLY_RESOLVED,
                    "Succeeded and improved, but still beyond threshold (" + check.evidenceId() + ")");
        }
        return conclude(p, inv, ProposalStatus.FAILED, Resolution.UNRESOLVED,
                "Action ran but verification shows the problem persists (" + check.evidenceId() + ")");
    }

    private ActionProposal conclude(ActionProposal p, Investigation inv, ProposalStatus status,
                                    Resolution resolution, String outcome) {
        p.finish(status, outcome);
        proposals.save(p);
        investigations.mutate(inv.getId(), i -> i.updateResolution(resolution, clock.instant()));
        audit.record(inv.getId(), "ACTION_OUTCOME", "SYSTEM", p.getActionName() + " " + status + ": " + outcome);
        note(inv.getId(), "Action " + p.getActionName() + " " + status + ". " + outcome + ". Resolution: " + resolution + ".");
        investigations.publish(inv.getId(), "action", p.getActionName() + " " + status);
        return p;
    }

    public ActionProposal reject(Long investigationId, Long proposalId, String user, Role role, String reason) {
        synchronized (locks.computeIfAbsent(proposalId, k -> new Object())) {
            ActionProposal p = load(investigationId, proposalId);
            if (p.getStatus() != ProposalStatus.PROPOSED) {
                throw new IllegalStateException("Proposal is not awaiting a decision (status " + p.getStatus() + ")");
            }
            if (!role.atLeast(p.getRequiredRole())) {
                throw new AccessDeniedException("Requires " + p.getRequiredRole() + " to decide");
            }
            p.decide(ProposalStatus.REJECTED, user, clock.instant(), reason);
            proposals.save(p);
            audit.record(investigationId, "ACTION_REJECTED", user, p.getActionName() + (reason == null ? "" : ": " + reason));
            note(investigationId, "Action " + p.getActionName() + " was rejected by " + user
                    + (reason == null || reason.isBlank() ? "." : ": " + reason));
            return p;
        }
    }

    private ActionProposal load(Long investigationId, Long proposalId) {
        ActionProposal p = proposals.findById(proposalId)
                .orElseThrow(() -> new NotFoundException("Proposal " + proposalId + " not found"));
        if (!p.getInvestigationId().equals(investigationId)) {
            throw new NotFoundException("Proposal " + proposalId + " not found");   // TC-APR-12
        }
        return p;
    }

    private void note(Long investigationId, String text) {
        messages.save(new Message(investigationId, MessageAuthor.SYSTEM, MessageType.NOTE, text, null, clock.instant()));
    }

    public static String manualText(ActionConfig action) {
        return action.instructions() != null && !action.instructions().isBlank()
                ? action.instructions()
                : "Perform '" + action.name() + "' manually.";
    }

    static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
