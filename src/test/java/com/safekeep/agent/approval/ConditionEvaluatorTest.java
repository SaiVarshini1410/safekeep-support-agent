package com.safekeep.agent.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Supports TC-APR-01, TC-APR-07, TC-APR-09: the verification conditions decide the outcome. */
class ConditionEvaluatorTest {

    private final Map<String, Object> result = Map.of("current", 61, "usagePercent", 92, "status", "PARTIAL_FAILURE",
            "stale", 0, "nested", Map.of("value", 5));
    private final Map<String, Double> thresholds = Map.of("memoryPercent", 85.0, "poolUsagePercent", 90.0);

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("TC-APR-13 Verification conditions")
    @CsvSource(delimiter = '|', value = {
            "current < thresholds.memoryPercent | true",
            "usagePercent < thresholds.poolUsagePercent | false",
            "usagePercent < 98 | true",
            "status == SUCCESS | false",
            "status == PARTIAL_FAILURE | true",
            "stale == 0 && current < 85 | true",
            "stale == 0 && current > 85 | false",
            "nested.value >= 5 | true",
            "missingField == 1 | false"
    })
    void conditions(String condition, boolean expected) {
        assertThat(ConditionEvaluator.evaluate(condition, result, thresholds)).isEqualTo(expected);
    }
}
