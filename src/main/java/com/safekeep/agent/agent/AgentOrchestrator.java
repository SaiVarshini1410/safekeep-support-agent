package com.safekeep.agent.agent;

import com.safekeep.agent.apps.ApplicationRegistry;
import com.safekeep.agent.apps.RegisteredApplication;
import com.safekeep.agent.apps.config.ActionConfig;
import com.safekeep.agent.approval.ApprovalService;
import com.safekeep.agent.common.Json;
import com.safekeep.agent.config.SafekeepProperties;
import com.safekeep.agent.investigation.*;
import com.safekeep.agent.llm.*;
import com.safekeep.agent.tools.ToolCallOutcome;
import com.safekeep.agent.tools.ToolGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** The LLM tool-calling loop (Spec Section 8.1). The model reasons; code enforces (P1). */
@Service
public class AgentOrchestrator implements InvestigationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final InvestigationService investigations;
    private final ApplicationRegistry registry;
    private final ToolGateway gateway;
    private final LlmClient llm;
    private final PromptBuilder prompts;
    private final ResponseValidator validator;
    private final ApprovalService approvals;
    private final FindingRepository findings;
    private final RecommendationRepository recommendations;
    private final MessageRepository messages;
    private final EventRecordRepository events;
    private final ToolCallRepository toolCalls;
    private final Json json;
    private final Clock clock;
    private final SafekeepProperties.Agent cfg;
    private final Map<Long, Object> runLocks = new ConcurrentHashMap<>();

    public AgentOrchestrator(InvestigationService investigations, ApplicationRegistry registry, ToolGateway gateway,
                             LlmClient llm, PromptBuilder prompts, ResponseValidator validator,
                             ApprovalService approvals, FindingRepository findings,
                             RecommendationRepository recommendations, MessageRepository messages,
                             EventRecordRepository events, ToolCallRepository toolCalls, Json json,
                             Clock clock, SafekeepProperties props) {
        this.investigations = investigations;
        this.registry = registry;
        this.gateway = gateway;
        this.llm = llm;
        this.prompts = prompts;
        this.validator = validator;
        this.approvals = approvals;
        this.findings = findings;
        this.recommendations = recommendations;
        this.messages = messages;
        this.events = events;
        this.toolCalls = toolCalls;
        this.json = json;
        this.clock = clock;
        this.cfg = props.agent();
    }

    @Override
    public void run(Long id) {
        synchronized (runLocks.computeIfAbsent(id, k -> new Object())) {   // one run per investigation at a time
            try {
                doRun(id);
            } catch (LlmUnavailableException e) {
                fail(id, "AI service unavailable");
            } catch (RuntimeException e) {
                log.error("Investigation {} failed", id, e);
                fail(id, "Unexpected error: " + e.getClass().getSimpleName());
            }
        }
    }

    private void doRun(Long id) {
        Investigation inv = investigations.get(id);
        if (inv.getState() == InvestigationState.RECEIVED) {
            inv = investigations.transition(id, InvestigationState.INVESTIGATING, null, "AGENT");
        } else if (inv.getState() != InvestigationState.INVESTIGATING) {
            return;
        }
        RegisteredApplication app = registry.find(inv.getApplicationId()).orElseThrow();
        List<LlmToolSpec> tools = prompts.toolSpecs(registry.readToolsFor(app.id(), inv.getEventType()));
        List<ActionConfig> actions = registry.enabledActions(app.id());

        List<LlmMessage> conv = new ArrayList<>();
        conv.add(LlmMessage.system(prompts.system(app, actions)));
        conv.add(LlmMessage.user(prompts.kickoff(inv, latestDetails(id), conversationContext(id),
                findings.findByInvestigationIdOrderByIdAsc(id))));

        boolean exhausted = false;
        loop:
        while (true) {
            if (inv.getToolCallsUsed() >= cfg.maxToolCalls()) {
                exhausted = true;
                break;
            }
            LlmResponse r = chatWithRetry(new LlmRequest(conv, tools, false));
            if (!r.hasToolCalls()) {
                if (!r.content().isBlank()) conv.add(LlmMessage.assistant(r.content(), null));
                break;
            }
            conv.add(LlmMessage.assistant(r.content(), r.toolCalls()));
            for (LlmToolCall call : r.toolCalls()) {
                if (inv.getToolCallsUsed() >= cfg.maxToolCalls()) {
                    exhausted = true;
                    break loop;
                }
                investigations.publish(id, "tool", "Calling " + call.name() + " " + call.arguments());
                ToolCallOutcome o = gateway.executeForAgent(inv, call.name(), call.arguments());
                inv = investigations.mutate(id, Investigation::recordToolCall);
                investigations.publish(id, "tool", o.evidenceId() + " " + call.name() + ": " + o.status()
                        + (o.errorCode() == null ? "" : " " + o.errorCode()));
                conv.add(LlmMessage.tool(call.name(), gateway.contentForModel(o, cfg.maxToolResultChars())));
            }
        }
        if (exhausted) {
            conv.add(LlmMessage.user(prompts.budgetExhausted(cfg.maxToolCalls())));
        }

        investigations.publish(id, "analysis", "Analyzing evidence");
        conv.add(LlmMessage.user(prompts.finalInstruction()));
        ValidationContext ctx = context(id, app);
        LlmResponse fin = chatWithRetry(new LlmRequest(conv, List.of(), true));
        ValidationResult vr = validator.validate(fin.content(), ctx);
        if (!vr.valid()) {
            conv.add(LlmMessage.assistant(fin.content(), null));
            conv.add(LlmMessage.user(prompts.repair(vr.error())));
            fin = chatWithRetry(new LlmRequest(conv, List.of(), true));
            vr = validator.validate(fin.content(), ctx);
            if (!vr.valid()) {
                fail(id, "Invalid AI output");
                return;
            }
        }
        ValidatedOutput out = vr.output();
        if (exhausted) {
            out = out.withMissingIfEmpty("Investigation stopped after " + cfg.maxToolCalls()
                    + " tool calls; some evidence may be missing.");
        }
        persist(id, out);
    }

    private void persist(Long id, ValidatedOutput out) {
        Instant now = clock.instant();
        findings.deleteByInvestigationId(id);
        recommendations.deleteByInvestigationId(id);
        out.findings().forEach(f -> findings.save(new Finding(id, f.kind(), f.statement(), f.evidenceIds(), f.downgraded())));
        out.recommendations().forEach(r -> recommendations.save(new Recommendation(id, r.text(), r.priority(), r.kind())));

        approvals.expirePending(id, "Superseded by a new analysis");
        Investigation inv = investigations.get(id);
        if (out.proposal() != null) {
            approvals.propose(inv, out.proposal().action(), out.proposal().params(), out.proposal().reason());
        }
        boolean recommended = !out.recommendations().isEmpty() || out.proposal() != null;
        investigations.mutate(id, i -> {
            i.recordAnalysis(out.summary(), out.missingInformation(), now);
            i.updateResolution(out.resolution(), now);
            if (recommended) i.markFirstRecommendation(now);
        });
        messages.save(new Message(id, MessageAuthor.AGENT, MessageType.NOTE, out.summary(), null, now));

        if (out.questionForUser() != null) {
            messages.save(new Message(id, MessageAuthor.AGENT, MessageType.AGENT_QUESTION, out.questionForUser(), null, now));
            investigations.transition(id, InvestigationState.WAITING_FOR_INFO, "Waiting for user reply", "AGENT");
        } else {
            investigations.transition(id, InvestigationState.COMPLETED, "Findings produced", "AGENT");
        }
    }

    private void fail(Long id, String reason) {
        Investigation inv = investigations.get(id);
        if (inv.getState() == InvestigationState.INVESTIGATING) {
            investigations.transition(id, InvestigationState.UNABLE_TO_COMPLETE, reason, "AGENT");
        }
        messages.save(new Message(id, MessageAuthor.SYSTEM, MessageType.NOTE,
                "Investigation stopped: " + reason + ". Evidence collected so far is kept.", null, clock.instant()));
    }

    /** Retries with backoff, then gives up (decision D11). */
    LlmResponse chatWithRetry(LlmRequest request) {
        LlmUnavailableException last = null;
        int attempts = 1 + cfg.llmRetries();
        for (int i = 0; i < attempts; i++) {
            try {
                return llm.chat(request);
            } catch (LlmUnavailableException e) {
                last = e;
                if (i < attempts - 1) sleep(cfg.retryBackoffMs() * (2L * i + 1));
            }
        }
        throw last;
    }

    private ValidationContext context(Long id, RegisteredApplication app) {
        Map<String, ToolCallStatus> evidence = new HashMap<>();
        toolCalls.findByInvestigationIdOrderByIdAsc(id).forEach(t -> evidence.put(t.getEvidenceId(), t.getStatus()));
        return new ValidationContext(evidence, app, name -> registry.isEnabled(app.id(), name));
    }

    private Object latestDetails(Long id) {
        List<EventRecord> list = events.findByInvestigationIdOrderByReceivedAtAsc(id);
        if (list.isEmpty()) return Map.of();
        try {
            return json.readMap(list.get(list.size() - 1).getPayload()).getOrDefault("details", Map.of());
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private List<Message> conversationContext(Long id) {
        return messages.findByInvestigationIdOrderByIdAsc(id).stream()
                .filter(m -> m.getType() == MessageType.CONTEXT || m.getType() == MessageType.REPLY
                        || m.getType() == MessageType.AGENT_QUESTION)
                .toList();
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
