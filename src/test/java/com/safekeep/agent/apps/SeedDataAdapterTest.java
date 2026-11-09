package com.safekeep.agent.apps;

import com.safekeep.agent.common.Json;
import com.safekeep.agent.support.TestConfigs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SeedDataAdapterTest {

    private final Json json = new Json();
    private final ApplicationRegistry registry = new ApplicationRegistry(TestConfigs.productionApps().applications(),
            Map.of("backup-server", "k1", "storage-server", "k2"));
    private final Map<String, AppAdapter> adapters = Map.of(
            "backup-server", new BackupServerAdapter(json, registry),
            "storage-server", new StorageServerAdapter(json, registry));

    private static Map<String, Object> args(String spec) {
        Map<String, Object> m = new HashMap<>();
        if (spec != null && !spec.isBlank()) {
            for (String kv : spec.split(";")) {
                String[] p = kv.split("=");
                m.put(p[0], p[1]);
            }
        }
        return m;
    }

    @ParameterizedTest(name = "{0} {2} -> {3} = {4}")
    @DisplayName("TC-ADP-01 Core scenarios return their raw values")
    @CsvSource(delimiter = '|', value = {
            "S-1A | backup-server  | get_job_sessions   |                    | stale             | 1172",
            "S-1A | backup-server  | get_metrics        | metric=memory      | current           | 93",
            "S-1B | backup-server  | get_metrics        | metric=login_rate  | current           | 1200",
            "S-1C | storage-server | get_pool_status    |                    | usagePercent      | 98",
            "S-2A | backup-server  | get_job_details    |                    | vmsFailed         | 6",
            "S-2C | backup-server  | get_job_details    |                    | error             | Task failed (code 1)",
            "S-3B | backup-server  | get_storage_targets|                    | targets           | OFFLINE",
            "S-4A | storage-server | get_system_logs    | boot=previous      | lines             | Kernel panic"
    })
    void scenarioValues(String scenario, String app, String tool, String argSpec, String field, String expected) {
        Map<String, Object> result = adapters.get(app).executeTool(scenario, tool, args(argSpec));
        assertThat(String.valueOf(result.get(field))).contains(expected);
    }

    @Test
    @DisplayName("TC-ADP-02 Seed data never contains the answer")
    void seedDataHasNoAnswers() throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:seed/**/*.json");
        assertThat(files).isNotEmpty();
        for (Resource r : files) {
            String text = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String banned : List.of("\"rootCause\"", "\"cause\"", "\"diagnosis\"", "\"explanation\"")) {
                assertThat(text).as(r.getFilename()).doesNotContain(banned);
            }
        }
    }

    @Test
    @DisplayName("TC-ADP-03 S-1A purge changes memory and stale sessions")
    void purgeEffects() {
        AppAdapter a = adapters.get("backup-server");
        a.reset("S-1A");
        a.executeAction("S-1A", "purge_stale_sessions", Map.of());
        assertThat(a.executeTool("S-1A", "get_metrics", Map.of("metric", "memory"))).containsEntry("current", 61);
        assertThat(a.executeTool("S-1A", "get_job_sessions", Map.of())).containsEntry("stale", 0);
    }

    @Test
    @DisplayName("TC-ADP-04 S-2A retry leaves the job failed (storage still full)")
    void retryDoesNotFix() {
        AppAdapter a = adapters.get("backup-server");
        a.reset("S-2A");
        a.executeAction("S-2A", "retry_failed_vms", Map.of());
        Map<String, Object> job = a.executeTool("S-2A", "get_job_details", Map.of());
        assertThat(job.get("status")).isEqualTo("PARTIAL_FAILURE");
        assertThat(String.valueOf(job.get("error"))).contains("insufficient space");
    }

    @Test
    @DisplayName("TC-ADP-05 S-3A shows the expired vCenter certificate")
    void expiredCertificate() {
        Map<String, Object> deps = adapters.get("backup-server").executeTool("S-3A", "get_dependency_status", Map.of());
        assertThat(json.write(deps)).contains("vcenter-prod").contains("certificate expired");
    }

    @Test
    @DisplayName("TC-ADP-06 Reset restores the initial scenario state")
    void resetRestores() {
        AppAdapter a = adapters.get("backup-server");
        a.executeAction("S-1A", "purge_stale_sessions", Map.of());
        a.reset("S-1A");
        assertThat(a.executeTool("S-1A", "get_job_sessions", Map.of())).containsEntry("stale", 1172);
    }

    @Test
    @DisplayName("TC-ADP-07 Log filtering and runbook search")
    void logsAndRunbooks() {
        AppAdapter a = adapters.get("backup-server");
        Map<String, Object> logs = a.executeTool("S-1A", "get_logs", Map.of("contains", "session-cleanup", "level", "WARN"));
        assertThat((List<?>) logs.get("lines")).isNotEmpty().allSatisfy(l -> assertThat(l.toString()).contains("session-cleanup"));
        Map<String, Object> rb = a.executeTool(null, "search_runbook", Map.of("query", "stale sessions memory"));
        assertThat((List<?>) rb.get("matches")).isNotEmpty();
    }
}
