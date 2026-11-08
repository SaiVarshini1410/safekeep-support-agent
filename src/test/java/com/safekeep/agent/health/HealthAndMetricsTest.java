package com.safekeep.agent.health;

import com.safekeep.agent.common.Masking;
import com.safekeep.agent.intake.IntakeFailure;
import com.safekeep.agent.intake.IntakeFailureLog;
import com.safekeep.agent.llm.LlmHealth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HealthAndMetricsTest {

    @Test
    @DisplayName("TC-HLT-01 LLM health status: DOWN when unreachable, DEGRADED when the model is missing")
    void llmStatus() {
        assertThat(new LlmHealth(false, false, "m", "x").status()).isEqualTo("DOWN");
        assertThat(new LlmHealth(true, false, "m", "model not pulled").status()).isEqualTo("DEGRADED");
        assertThat(new LlmHealth(true, true, "m", null).status()).isEqualTo("UP");
    }

    @Test
    @DisplayName("TC-HLT-02 Intake failure log keeps the latest 50, newest first")
    void failureLogBounded() {
        IntakeFailureLog log = new IntakeFailureLog(Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC));
        for (int i = 0; i < 60; i++) log.record("app", "reason " + i, "x");
        List<IntakeFailure> list = log.list();
        assertThat(list).hasSize(50);
        assertThat(list.get(0).reason()).isEqualTo("reason 59");
    }

    @Test
    @DisplayName("TC-MET-01 Median time to first recommendation")
    void median() {
        assertThat(MetricsService.median(List.of(10L, 20L, 40L))).isEqualTo(20.0);
        assertThat(MetricsService.median(List.of())).isNull();
    }

    @Test
    @DisplayName("TC-MET-02 Percentages for evidence-backed findings and positive feedback")
    void percentages() {
        assertThat(MetricsService.pct(3, 4)).isEqualTo(75.0);
        assertThat(MetricsService.pct(2, 3)).isEqualTo(66.7);
        assertThat(MetricsService.pct(1, 0)).isNull();
    }

    @Test
    @DisplayName("TC-SEC-04 Secrets are masked before logging or storing")
    void masking() {
        assertThat(Masking.mask("calling with bk-test-key now", List.of("bk-test-key")))
                .isEqualTo("calling with **** now");
    }
}
