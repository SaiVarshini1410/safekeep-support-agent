package com.safekeep.agent.tools;

import com.safekeep.agent.apps.AdapterRegistry;
import com.safekeep.agent.apps.AppAdapter;
import com.safekeep.agent.apps.ApplicationRegistry;
import com.safekeep.agent.apps.config.*;
import com.safekeep.agent.audit.AuditService;
import com.safekeep.agent.common.Json;
import com.safekeep.agent.config.SafekeepProperties;
import com.safekeep.agent.investigation.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ToolGatewayTest {

    private static final String SECRET = "secret-key-123";

    private final List<String> adapterCalls = new ArrayList<>();
    private final List<Map<String, Object>> adapterArgs = new ArrayList<>();
    private final Map<String, Runnable> behaviour = new HashMap<>();
    private final List<ToolCall> saved = new ArrayList<>();
    private ApplicationRegistry registry;
    private AuditService audit;
    private ToolGateway gateway;
    private Investigation inv;

    @BeforeEach
    void setUp() {
        ParamConfig metric = new ParamConfig("metric", "string", List.of("memory", "cpu"), null, null, null, true, null);
        ParamConfig hours = new ParamConfig("hours", "integer", null, 1, 168, "24", false, null);
        ApplicationConfig cfg = new ApplicationConfig("App", "d", Map.of(), List.of("EVT"),
                List.of(new ToolConfig("get_metrics", "m", null, List.of(metric, hours)),
                        new ToolConfig("get_logs", "l", null, null)),
                List.of(new ActionConfig("purge", "p", "LOW", null, null, new VerifyConfig("get_metrics", null, "x == 1", null), null, null)),
                List.of());
        registry = new ApplicationRegistry(Map.of("app", cfg), Map.of("app", SECRET));

        AppAdapter adapter = new AppAdapter() {
            @Override public String applicationId() { return "app"; }
            @Override public Map<String, Object> executeTool(String s, String tool, Map<String, Object> args) {
                adapterCalls.add(tool);
                adapterArgs.add(args);
                Runnable r = behaviour.get(tool);
                if (r != null) r.run();
                return Map.of("ok", true);
            }
            @Override public Map<String, Object> executeAction(String s, String action, Map<String, Object> args) {
                adapterCalls.add(action);
                return Map.of("ok", true);
            }
            @Override public void reset(String s) { }
        };

        ToolCallRepository repo = mock(ToolCallRepository.class);
        AtomicLong count = new AtomicLong();
        when(repo.countByInvestigationId(any())).thenAnswer(i -> count.get());
        when(repo.save(any(ToolCall.class))).thenAnswer(i -> {
            count.incrementAndGet();
            saved.add(i.getArgument(0));
            return i.getArgument(0);
        });
        audit = mock(AuditService.class);
        SafekeepProperties props = new SafekeepProperties(Map.of(), Map.of(),
                new SafekeepProperties.Agent(null, null, null, null, null, null, null, 1, null, null, null, null));
        gateway = new ToolGateway(registry, new AdapterRegistry(List.of(adapter)), repo, audit, new Json(),
                Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC), props);
        inv = Investigation.open(new CorrelationKey("app", "EVT", "r1"), Instant.parse("2026-11-07T10:00:00Z"));
    }

    @Test
    @DisplayName("TC-GW-01 Unknown tool is denied and never reaches the adapter")
    void unknownTool() {
        ToolCallOutcome o = gateway.executeForAgent(inv, "get_passwords", Map.of());
        assertThat(o.status()).isEqualTo(ToolCallStatus.DENIED);
        assertThat(o.errorCode()).isEqualTo("UNKNOWN_TOOL");
        assertThat(adapterCalls).isEmpty();
        assertThat(saved).singleElement().extracting(ToolCall::getStatus).isEqualTo(ToolCallStatus.DENIED);
    }

    @Test
    @DisplayName("TC-GW-02 Disabled tool is denied")
    void disabledTool() {
        registry.setEnabled("app", "get_logs", false);
        ToolCallOutcome o = gateway.executeForAgent(inv, "get_logs", Map.of());
        assertThat(o.errorCode()).isEqualTo("TOOL_DISABLED");
        assertThat(adapterCalls).isEmpty();
    }

    @Test
    @DisplayName("TC-GW-03 An ACTION cannot be called from the agent loop")
    void actionNotCallable() {
        ToolCallOutcome o = gateway.executeForAgent(inv, "purge", Map.of());
        assertThat(o.errorCode()).isEqualTo("ACTION_NOT_CALLABLE");
        assertThat(adapterCalls).isEmpty();
    }

    @Test
    @DisplayName("TC-GW-04 Safe coercion and defaults")
    void coercionAndDefaults() {
        gateway.executeForAgent(inv, "get_metrics", Map.of("metric", "memory", "hours", "24"));
        gateway.executeForAgent(inv, "get_metrics", Map.of("metric", "MEMORY"));
        assertThat(adapterArgs.get(0)).containsEntry("hours", 24);
        assertThat(adapterArgs.get(1)).containsEntry("hours", 24).containsEntry("metric", "memory");
    }

    static Stream<Arguments> invalidArgs() {
        return Stream.of(
                Arguments.of("hours=\"0\" (seen from llama3.2 in the model spike)", Map.of("metric", "memory", "hours", "0"), "hours"),
                Arguments.of("hours=500", Map.of("metric", "memory", "hours", 500), "hours"),
                Arguments.of("hours=\"abc\"", Map.of("metric", "memory", "hours", "abc"), "hours"),
                Arguments.of("metric=disk", Map.of("metric", "disk"), "metric"),
                Arguments.of("missing required", Map.of(), "metric"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidArgs")
    @DisplayName("TC-GW-05 Invalid arguments are rejected before execution")
    void invalidArguments(String row, Map<String, Object> args, String field) {
        ToolCallOutcome o = gateway.executeForAgent(inv, "get_metrics", args);
        assertThat(o.errorCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(o.message()).contains(field);
        assertThat(adapterCalls).isEmpty();
    }

    @Test
    @DisplayName("TC-GW-09 Unknown arguments are ignored and reported; echoed schema objects fall back to defaults")
    void lenientArguments() {
        ToolCallOutcome o = gateway.executeForAgent(inv, "get_metrics",
                Map.of("metric", "memory", "hours", Map.of("type", "integer"), "text", "abc"));
        assertThat(o.status()).isEqualTo(ToolCallStatus.OK);
        assertThat(adapterArgs.get(0)).containsEntry("hours", 24).doesNotContainKey("text");
        assertThat(o.result()).containsEntry("_ignoredArguments", List.of("text"));
    }

    @Test
    @DisplayName("TC-GW-06 Slow adapter times out")
    void timeout() {
        behaviour.put("get_logs", () -> {
            try { Thread.sleep(2500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        ToolCallOutcome o = gateway.executeForAgent(inv, "get_logs", Map.of());
        assertThat(o.status()).isEqualTo(ToolCallStatus.ERROR);
        assertThat(o.errorCode()).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("TC-GW-07 Adapter errors are sanitized")
    void adapterErrorSanitized() {
        behaviour.put("get_logs", () -> { throw new IllegalStateException("failed using key " + SECRET); });
        ToolCallOutcome o = gateway.executeForAgent(inv, "get_logs", Map.of());
        assertThat(o.errorCode()).isEqualTo("ADAPTER_ERROR");
        assertThat(o.message()).doesNotContain(SECRET).contains("****");
    }

    @Test
    @DisplayName("TC-GW-08 Every call (OK, DENIED, ERROR) is recorded and audited")
    void everythingRecorded() {
        behaviour.put("get_logs", () -> { throw new IllegalStateException("boom"); });
        gateway.executeForAgent(inv, "get_metrics", Map.of("metric", "cpu"));
        gateway.executeForAgent(inv, "nope", Map.of());
        gateway.executeForAgent(inv, "get_logs", Map.of());
        assertThat(saved).extracting(ToolCall::getStatus)
                .containsExactly(ToolCallStatus.OK, ToolCallStatus.DENIED, ToolCallStatus.ERROR);
        assertThat(saved).extracting(ToolCall::getEvidenceId).containsExactly("E1", "E2", "E3");
        verify(audit, times(3)).record(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("TC-AGT-13 Content sent to the model is capped")
    void contentCapped() {
        ToolCallOutcome big = new ToolCallOutcome("E1", "get_logs", ToolCallStatus.OK, null, null,
                Map.of("lines", "x".repeat(5000)));
        String text = gateway.contentForModel(big, 1500);
        assertThat(text.length()).isLessThanOrEqualTo(1500);
        assertThat(text).startsWith("[E1]").endsWith("[truncated]");
    }
}
