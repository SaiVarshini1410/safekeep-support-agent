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
        List<ActionConfig> actions = registry.enabledActions(app.id(), inv.getEventType());

        List<LlmMessage> conv = new ArrayList<>();
        conv.add(LlmMessage.system(prompts.system(app, actions)));
        conv.add(LlmMessage.user(prompts.kickoff(inv, latestDetails(id), conversationContext(id),
                findings.findByInvestigationIdOrderByIdAsc(id))));

        RunState st = new RunState(conv, tools.stream().map(LlmToolSpec::name).toList());
        ValidationContext ctx;
        ValidatedOutput out;
        int round = 0;
        while (true) {
            inv = toolPhase(id, inv, tools, st);
            if (st.exhausted) {
                conv.add(LlmMessage.user(prompts.budgetExhausted(cfg.maxToolCalls())));
            }
            investigations.publish(id, "analysis", "Analyzing evidence");
            conv.add(LlmMessage.user(prompts.finalInstruction(evidenceDigest(id), actions)));
            ctx = context(id, app, inv.getEventType());
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
            out = vr.output();
            // Guard: do not ask the user while tools that could answer are still unused (one extra round)
            List<String> unused = st.unused();
            if (out.questionForUser() != null && round == 0 && !st.exhausted && !unused.isEmpty()) {
                round++;
                conv.add(LlmMessage.assistant(fin.content(), null));
                conv.add(LlmMessage.user(prompts.useToolsBeforeAsking(unused)));
                investigations.publish(id, "analysis", "Using remaining tools before asking the user");
                continue;
            }
            break;
        }
        if (st.exhausted) {
            out = out.withMissingIfEmpty("Investigation stopped after " + cfg.maxToolCalls()
                    + " tool calls; some evidence may be missing.");
        }
        persist(id, out);
    }

    /** Mutable state of one run's tool phase. */
    private static final class RunState {
        final List<LlmMessage> conv;
        final List<String> toolNames;
        final Set<String> used = new HashSet<>();
        final Map<String, String> callCache = new HashMap<>();
        int nudges;
        boolean exhausted;

        RunState(List<LlmMessage> conv, List<String> toolNames) {
            this.conv = conv;
            this.toolNames = toolNames;
        }

        List<String> unused() {
            return toolNames.stream().filter(n -> !used.contains(n)).toList();
        }
    }

    private Investigation toolPhase(Long id, Investigation inv, List<LlmToolSpec> tools, RunState st) {
        while (true) {
            if (inv.getToolCallsUsed() >= cfg.maxToolCalls()) {
                st.exhausted = true;
                return inv;
            }
            LlmResponse r = chatWithRetry(new LlmRequest(st.conv, tools, false));
            if (!r.hasToolCalls()) {
                if (!r.content().isBlank()) st.conv.add(LlmMessage.assistant(r.content(), null));
                List<String> unused = st.unused();
                // Small models stop early: code nudges them toward unused tools (bounded)
                if (inv.getToolCallsUsed() < cfg.minToolCalls() && !unused.isEmpty() && st.nudges < 2) {
                    st.nudges++;
                    st.conv.add(LlmMessage.user(prompts.nudge(unused)));
                    investigations.publish(id, "analysis", "Asking the model to gather more evidence");
                    continue;
                }
                return inv;
            }
            st.conv.add(LlmMessage.assistant(r.content(), r.toolCalls()));
            for (LlmToolCall call : r.toolCalls()) {
                if (inv.getToolCallsUsed() >= cfg.maxToolCalls()) {
                    st.exhausted = true;
                    return inv;
                }
                String cacheKey = call.name() + json.write(new TreeMap<>(call.arguments()));
                String previous = st.callCache.get(cacheKey);
                inv = investigations.mutate(id, Investigation::recordToolCall);
                if (previous != null) {      // Guard: identical repeat is not executed again
                    st.conv.add(LlmMessage.tool(call.name(), "Already called with the same arguments; see "
                            + previous + ". Use a different tool."));
                    investigations.publish(id, "tool", "Skipped repeated call " + call.name() + " (see " + previous + ")");
                    continue;
                }
                investigations.publish(id, "tool", "Calling " + call.name() + " " + call.arguments());
                ToolCallOutcome o = gateway.executeForAgent(inv, call.name(), call.arguments());
                st.callCache.put(cacheKey, o.evidenceId());
                if (o.ok()) st.used.add(call.name());     // only successful calls count as "used" (F8)
                investigations.publish(id, "tool", o.evidenceId() + " " + call.name() + ": " + o.status()
                        + (o.errorCode() == null ? "" : " " + o.errorCode()));
                st.conv.add(LlmMessage.tool(call.name(), gateway.contentForModel(o, cfg.maxToolResultChars())));
            }
        }
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

    private ValidationContext context(Long id, RegisteredApplication app, String eventType) {
        Map<String, ToolCallStatus> evidence = new HashMap<>();
        toolCalls.findByInvestigationIdOrderByIdAsc(id).forEach(t -> evidence.put(t.getEvidenceId(), t.getStatus()));
        return new ValidationContext(evidence, app, name -> registry.isEnabled(app.id(), name), eventType);
    }

    /** Re-lists every evidence item right before the final answer: small models lose track of earlier results. */
    private String evidenceDigest(Long id) {
        StringBuilder sb = new StringBuilder();
        for (ToolCall t : toolCalls.findByInvestigationIdOrderByIdAsc(id)) {
            if (t.isAction()) continue;
            String r = t.getResultJson() == null ? "" : t.getResultJson();
            if (r.length() > 600) r = r.substring(0, 600) + "...";
            sb.append('[').append(t.getEvidenceId()).append("] ").append(t.getToolName()).append(' ')
              .append(t.getArgsJson()).append(" -> ").append(t.getStatus()).append(": ").append(r).append('\n');
        }
        return sb.isEmpty() ? "(none)\n" : sb.toString();
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
