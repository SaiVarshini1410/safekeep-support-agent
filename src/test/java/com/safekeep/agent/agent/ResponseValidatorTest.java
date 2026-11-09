package com.safekeep.agent.agent;

import com.safekeep.agent.apps.RegisteredApplication;
import com.safekeep.agent.apps.config.ActionConfig;
import com.safekeep.agent.apps.config.ApplicationConfig;
import com.safekeep.agent.apps.config.ToolConfig;
import com.safekeep.agent.apps.config.VerifyConfig;
import com.safekeep.agent.common.Json;
import com.safekeep.agent.investigation.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseValidatorTest {

    private final ResponseValidator validator = new ResponseValidator(new Json());
    private final Set<String> disabled = new HashSet<>();
    private final RegisteredApplication app = new RegisteredApplication("backup-server",
            new ApplicationConfig("Backup Server", "desc", Map.of(), List.of("HIGH_MEMORY"),
                    List.of(new ToolConfig("get_metrics", "m", null, null)),
                    List.of(new ActionConfig("purge_stale_sessions", "Purge", "LOW", null, null,
                            new VerifyConfig("get_metrics", null, "current < 85", null), "Release them in the console.")),
                    List.of()),
            "key", true, null);
    private final ValidationContext ctx = new ValidationContext(
            Map.of("E1", ToolCallStatus.OK, "E2", ToolCallStatus.OK, "E3", ToolCallStatus.ERROR),
            app, name -> !disabled.contains(name));

    private static String output(String findings, String extra) {
        return "{\"summary\":\"s\",\"findings\":[" + findings + "]" + extra + "}";
    }

    private static String finding(String kind, String ids) {
        return "{\"kind\":\"" + kind + "\",\"statement\":\"Heap usage is 93%\",\"evidenceIds\":[" + ids + "]}";
    }

    @Test
    @DisplayName("TC-VAL-01 CONFIRMED finding with valid evidence stays CONFIRMED")
    void confirmedWithEvidenceStays() {
        ValidationResult r = validator.validate(output(finding("CONFIRMED", "\"E1\""), ""), ctx);
        ValidatedFinding f = r.output().findings().get(0);
        assertThat(f.kind()).isEqualTo(FindingKind.CONFIRMED);
        assertThat(f.downgraded()).isFalse();
    }

    @Test
    @DisplayName("TC-VAL-02 CONFIRMED finding without evidence is downgraded")
    void confirmedWithoutEvidenceIsDowngraded() {
        ValidatedFinding f = validator.validate(output(finding("CONFIRMED", ""), ""), ctx).output().findings().get(0);
        assertThat(f.kind()).isEqualTo(FindingKind.SUSPECTED);
        assertThat(f.downgraded()).isTrue();
    }

    @Test
    @DisplayName("TC-VAL-03 CONFIRMED finding citing only failed evidence is downgraded")
    void confirmedWithOnlyErrorEvidenceIsDowngraded() {
        ValidatedFinding f = validator.validate(output(finding("CONFIRMED", "\"E3\""), ""), ctx).output().findings().get(0);
        assertThat(f.kind()).isEqualTo(FindingKind.SUSPECTED);
        assertThat(f.downgraded()).isTrue();
    }

    @Test
    @DisplayName("TC-VAL-04 Unknown evidence IDs are removed")
    void unknownEvidenceRemoved() {
        ValidatedFinding f = validator.validate(output(finding("SUSPECTED", "\"E1\",\"E9\""), ""), ctx).output().findings().get(0);
        assertThat(f.evidenceIds()).containsExactly("E1");
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("TC-VAL-05 Unavailable action becomes a manual recommendation")
    @CsvSource({
            "unknown action, reboot_world, is not available",
            "disabled action, purge_stale_sessions, Release them in the console.",
            "other application's action, run_expiration_cleanup, is not available"
    })
    void unavailableActionBecomesManual(String row, String action, String expectedText) {
        disabled.add("purge_stale_sessions");
        String extra = ",\"proposedAction\":{\"name\":\"" + action + "\",\"params\":{}}";
        ValidatedOutput out = validator.validate(output(finding("SUSPECTED", "\"E1\""), extra), ctx).output();
        assertThat(out.proposal()).isNull();
        assertThat(out.recommendations()).anySatisfy(rec -> {
            assertThat(rec.kind()).isEqualTo(RecommendationKind.MANUAL);
            assertThat(rec.text()).contains(expectedText);
        });
    }

    @Test
    @DisplayName("TC-VAL-05 (positive) Enabled action of this application is kept as a proposal")
    void enabledActionKept() {
        String extra = ",\"proposedAction\":{\"name\":\"purge_stale_sessions\",\"params\":{},\"reason\":\"r\"},\"resolution\":\"UNRESOLVED\"";
        ValidatedOutput out = validator.validate(output(finding("SUSPECTED", "\"E1\""), extra), ctx).output();
        assertThat(out.proposal()).isNotNull();
        assertThat(out.proposal().action().name()).isEqualTo("purge_stale_sessions");
    }

    @Test
    @DisplayName("TC-VAL-10 No action is proposed when the problem is not shown to be present")
    void noProposalWithoutUnresolvedProblem() {
        String extra = ",\"proposedAction\":{\"name\":\"purge_stale_sessions\",\"params\":{}},\"resolution\":\"UNKNOWN\"";
        ValidatedOutput out = validator.validate(output(finding("SUSPECTED", "\"E1\""), extra), ctx).output();
        assertThat(out.proposal()).isNull();
        assertThat(out.recommendations()).anySatisfy(r -> assertThat(r.text()).contains("was not proposed"));
    }

    @Test
    @DisplayName("TC-VAL-06 No findings and no missing information -> cause could not be determined")
    void emptyOutputGetsMissingInformation() {
        ValidatedOutput out = validator.validate("{\"summary\":\"s\",\"findings\":[],\"missingInformation\":[],\"resolution\":\"UNRESOLVED\"}", ctx).output();
        assertThat(out.missingInformation()).containsExactly("Cause could not be determined");
        assertThat(out.resolution()).isEqualTo(Resolution.UNKNOWN);
    }

    @ParameterizedTest(name = "model says {0}")
    @DisplayName("TC-VAL-07 The model cannot set RESOLVED or PARTIALLY_RESOLVED")
    @CsvSource({"RESOLVED", "PARTIALLY_RESOLVED"})
    void modelCannotResolve(String claimed) {
        ValidatedOutput out = validator.validate(output(finding("SUSPECTED", "\"E1\""), ",\"resolution\":\"" + claimed + "\""), ctx).output();
        assertThat(out.resolution()).isEqualTo(Resolution.UNKNOWN);
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("TC-VAL-08 Malformed output is invalid with a readable error")
    @CsvSource(delimiter = '|', value = {
            "not json | this is not json",
            "missing findings | {\"summary\":\"s\"}",
            "findings wrong type | {\"summary\":\"s\",\"findings\":\"none\"}"
    })
    void malformedIsInvalid(String row, String raw) {
        ValidationResult r = validator.validate(raw, ctx);
        assertThat(r.valid()).isFalse();
        assertThat(r.error()).isNotBlank();
    }

    @Test
    @DisplayName("TC-VAL-09 A question for the user leads to WAITING_FOR_INFO")
    void questionMeansWaiting() {
        ValidatedOutput out = validator.validate(output(finding("SUSPECTED", "\"E1\""), ",\"questionForUser\":\"Any change last night?\""), ctx).output();
        assertThat(out.nextState()).isEqualTo(InvestigationState.WAITING_FOR_INFO);
        assertThat(out.questionForUser()).isEqualTo("Any change last night?");
    }
}
