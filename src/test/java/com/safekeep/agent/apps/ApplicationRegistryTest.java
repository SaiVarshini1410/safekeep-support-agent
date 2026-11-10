package com.safekeep.agent.apps;

import com.safekeep.agent.apps.config.*;
import com.safekeep.agent.config.SafekeepProperties;
import com.safekeep.agent.security.Role;
import com.safekeep.agent.support.TestConfigs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationRegistryTest {

    private final SafekeepProperties props = TestConfigs.productionApps();

    @Test
    @DisplayName("TC-CFG-01 Production YAML loads both applications with tools, subsets and actions")
    void loadsProductionConfig() {
        ApplicationRegistry r = new ApplicationRegistry(props.applications(),
                Map.of("backup-server", "k1", "storage-server", "k2"));
        assertThat(r.all()).extracting(RegisteredApplication::id).containsExactly("backup-server", "storage-server");
        assertThat(r.find("backup-server").orElseThrow().config().eventTypes()).hasSize(6);
        assertThat(r.readToolsFor("backup-server", "HIGH_MEMORY")).extracting(ToolConfig::name)
                .containsExactlyInAnyOrder("get_metrics", "get_top_processes", "get_job_sessions", "get_logs",
                        "get_recent_changes", "search_runbook");
        assertThat(r.readToolsFor("backup-server", "HIGH_MEMORY")).hasSizeLessThanOrEqualTo(7);
        ActionConfig cleanup = r.find("storage-server").orElseThrow().action("run_expiration_cleanup").orElseThrow();
        assertThat(cleanup.risk()).isEqualTo("HIGH");
        assertThat(cleanup.requiredRole()).isEqualTo(Role.ADMIN);
        assertThat(r.find("backup-server").orElseThrow().action("retry_report_job").orElseThrow().requiredRole())
                .isEqualTo(Role.APP_USER);
    }

    @Test
    @DisplayName("TC-CFG-02a Action without requiredRole defaults to SUPPORT_ENGINEER")
    void defaultRole() {
        ActionConfig a = new ActionConfig("x", "d", null, null, null, new VerifyConfig("t", null, "a == 1", null), null, null);
        assertThat(a.requiredRole()).isEqualTo(Role.SUPPORT_ENGINEER);
    }

    @Test
    @DisplayName("TC-CFG-02b Action without verify configuration fails at startup with a clear message")
    void actionWithoutVerifyFails() {
        ApplicationConfig cfg = new ApplicationConfig("Bad", "d", Map.of(), List.of("E"),
                List.of(new ToolConfig("t", "d", null, null)),
                List.of(new ActionConfig("restart", "d", null, null, null, null, null, null)), List.of());
        assertThatThrownBy(() -> new ApplicationRegistry(Map.of("bad-app", cfg), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad-app").hasMessageContaining("restart");
    }

    @Test
    @DisplayName("TC-CFG-07 Actions are offered only for the event types they fit")
    void actionsScopedToEventTypes() {
        ApplicationRegistry r = new ApplicationRegistry(props.applications(),
                Map.of("backup-server", "k1", "storage-server", "k2"));
        assertThat(r.enabledActions("backup-server", "HIGH_CPU")).extracting(ActionConfig::name)
                .containsExactly("clear_expired_auth_sessions");
        assertThat(r.enabledActions("backup-server", "JOB_FAILED")).extracting(ActionConfig::name)
                .containsExactly("retry_failed_vms");
        assertThat(r.enabledActions("backup-server", "DATASOURCE_UNHEALTHY")).isEmpty();
        assertThat(r.enabledActions("storage-server", "UNEXPECTED_RESTART")).isEmpty();
    }

    @Test
    @DisplayName("TC-CFG-05 Missing API key marks the application unavailable")
    void missingKeyUnavailable() {
        ApplicationRegistry r = new ApplicationRegistry(props.applications(), Map.of("backup-server", "k1"));
        RegisteredApplication storage = r.find("storage-server").orElseThrow();
        assertThat(storage.available()).isFalse();
        assertThat(storage.error()).contains("API key");
        assertThat(r.findByApiKey("")).isEmpty();
        assertThat(r.findByApiKey("k1")).map(RegisteredApplication::id).contains("backup-server");
    }

    @Test
    @DisplayName("TC-CFG-06 Application toString never reveals the API key")
    void toStringHidesKey() {
        ApplicationRegistry r = new ApplicationRegistry(props.applications(), Map.of("backup-server", "very-secret-key"));
        assertThat(r.find("backup-server").orElseThrow().toString()).doesNotContain("very-secret-key");
    }
}
