package com.safekeep.agent.tools;

import com.safekeep.agent.apps.*;
import com.safekeep.agent.apps.config.ActionConfig;
import com.safekeep.agent.apps.config.ParamConfig;
import com.safekeep.agent.apps.config.ToolConfig;
import com.safekeep.agent.audit.AuditService;
import com.safekeep.agent.common.Json;
import com.safekeep.agent.common.Masking;
import com.safekeep.agent.config.SafekeepProperties;
import com.safekeep.agent.investigation.Investigation;
import com.safekeep.agent.investigation.ToolCall;
import com.safekeep.agent.investigation.ToolCallRepository;
import com.safekeep.agent.investigation.ToolCallStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * Single entry point for every tool and action execution (Spec Section 9.2, steps G1-G6).
 * The agent loop can only reach READ tools; actions are only reachable through approval (P5).
 */
@Component
public class ToolGateway {

    public enum Mode { AGENT, SYSTEM_READ, ACTION }

    private final ApplicationRegistry registry;
    private final AdapterRegistry adapters;
    private final ToolCallRepository toolCalls;
    private final AuditService audit;
    private final Json json;
    private final Clock clock;
    private final int timeoutSeconds;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tool-exec");
        t.setDaemon(true);
        return t;
    });

    public ToolGateway(ApplicationRegistry registry, AdapterRegistry adapters, ToolCallRepository toolCalls,
                       AuditService audit, Json json, Clock clock, SafekeepProperties props) {
        this.registry = registry;
        this.adapters = adapters;
        this.toolCalls = toolCalls;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.timeoutSeconds = props.agent().toolTimeoutSeconds();
    }

    public ToolCallOutcome executeForAgent(Investigation inv, String tool, Map<String, Object> args) {
        return execute(inv, tool, args, Mode.AGENT, "AGENT", null);
    }

    public ToolCallOutcome executeRead(Investigation inv, String tool, Map<String, Object> args, String actor) {
        return execute(inv, tool, args, Mode.SYSTEM_READ, actor, null);
    }

    public ToolCallOutcome executeAction(Investigation inv, String action, Map<String, Object> args,
                                         String actor, Long proposalId) {
        return execute(inv, action, args, Mode.ACTION, actor, proposalId);
    }

    private ToolCallOutcome execute(Investigation inv, String name, Map<String, Object> args,
                                    Mode mode, String actor, Long proposalId) {
        long start = System.nanoTime();
        String appId = inv.getApplicationId();
        Optional<RegisteredApplication> app = registry.find(appId);
        Optional<ToolConfig> tool = app.flatMap(a -> a.tool(name));
        Optional<ActionConfig> action = app.flatMap(a -> a.action(name));
        boolean isAction = mode == Mode.ACTION;

        // G1 + G3: does it exist, and may this caller reach it?
        if (mode != Mode.ACTION && tool.isEmpty()) {
            if (action.isPresent()) {
                return record(inv, name, args, ToolCallStatus.DENIED, "ACTION_NOT_CALLABLE",
                        "'" + name + "' is an action. Actions can only be proposed and need human approval.",
                        null, start, actor, false, null);
            }
            return record(inv, name, args, ToolCallStatus.DENIED, "UNKNOWN_TOOL",
                    "Tool '" + name + "' is not defined for " + appId, null, start, actor, false, null);
        }
        if (isAction && action.isEmpty()) {
            return record(inv, name, args, ToolCallStatus.DENIED, "UNKNOWN_TOOL",
                    "Action '" + name + "' is not defined for " + appId, null, start, actor, true, proposalId);
        }
        // G2: enabled?
        if (!registry.isEnabled(appId, name)) {
            return record(inv, name, args, ToolCallStatus.DENIED, "TOOL_DISABLED",
                    "'" + name + "' is disabled by an administrator", null, start, actor, isAction, proposalId);
        }
        // G4: arguments
        List<ParamConfig> params = isAction ? action.get().parameters() : tool.get().parameters();
        List<String> ignored = ArgumentValidator.unknownArguments(params, args);
        Map<String, Object> validated;
        try {
            validated = ArgumentValidator.validate(params, args);
        } catch (InvalidArgumentException e) {
            return record(inv, name, args, ToolCallStatus.DENIED, "INVALID_ARGUMENT", e.getMessage(),
                    null, start, actor, isAction, proposalId);
        }
        // G5: execute with timeout
        Optional<AppAdapter> adapter = adapters.get(appId);
        if (adapter.isEmpty()) {
            return record(inv, name, validated, ToolCallStatus.ERROR, "ADAPTER_ERROR",
                    "No adapter registered for " + appId, null, start, actor, isAction, proposalId);
        }
        Future<Map<String, Object>> future = executor.submit(() -> isAction
                ? adapter.get().executeAction(inv.getScenarioId(), name, validated)
                : adapter.get().executeTool(inv.getScenarioId(), name, validated));
        try {
            Map<String, Object> result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (!ignored.isEmpty()) {
                result = new java.util.LinkedHashMap<>(result);
                result.put("_ignoredArguments", ignored);   // tell the model which names were not understood
            }
            return record(inv, name, validated, ToolCallStatus.OK, null, null, result, start, actor, isAction, proposalId);
        } catch (TimeoutException e) {
            future.cancel(true);
            return record(inv, name, validated, ToolCallStatus.ERROR, "TIMEOUT",
                    "No response within " + timeoutSeconds + " s", null, start, actor, isAction, proposalId);
        } catch (ExecutionException e) {
            String msg = e.getCause() == null ? "Adapter error" : e.getCause().getMessage();
            return record(inv, name, validated, ToolCallStatus.ERROR, "ADAPTER_ERROR",
                    Masking.mask(msg, registry.allApiKeys()), null, start, actor, isAction, proposalId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return record(inv, name, validated, ToolCallStatus.ERROR, "ADAPTER_ERROR", "Interrupted",
                    null, start, actor, isAction, proposalId);
        }
    }

    /** G6: every request, including denied ones, becomes evidence and an audit entry. */
    private ToolCallOutcome record(Investigation inv, String name, Map<String, Object> args, ToolCallStatus status,
                                   String code, String message, Map<String, Object> result, long start,
                                   String actor, boolean isAction, Long proposalId) {
        long ms = (System.nanoTime() - start) / 1_000_000;
        String evidenceId = "E" + (toolCalls.countByInvestigationId(inv.getId()) + 1);
        String resultJson = result != null ? json.write(result) : json.write(Map.of("error", code, "message", message));
        toolCalls.save(new ToolCall(inv.getId(), evidenceId, name, json.write(args == null ? Map.of() : args),
                status, code, resultJson, ms, actor, isAction, proposalId, clock.instant()));
        audit.record(inv.getId(), isAction ? "ACTION_EXECUTED" : "TOOL_CALL", actor,
                evidenceId + " " + name + " " + status + (code == null ? "" : " " + code));
        return new ToolCallOutcome(evidenceId, name, status, code, message, result);
    }

    /** Text sent back to the model, capped in size (Spec Section 8.3). */
    public String contentForModel(ToolCallOutcome o, int maxChars) {
        String body = o.ok() ? json.write(o.result())
                : json.write(Map.of("error", o.errorCode(), "message", String.valueOf(o.message())));
        String text = "[" + o.evidenceId() + "] " + body;
        if (text.length() > maxChars) {
            text = text.substring(0, Math.max(0, maxChars - 20)) + " ...[truncated]";
        }
        return text;
    }
}
