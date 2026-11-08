package com.safekeep.agent.api;

import com.safekeep.agent.investigation.*;
import com.safekeep.agent.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.safekeep.agent.support.FakeLlmClient.*;
import static org.assertj.core.api.Assertions.assertThat;

class UserInteractionTest extends IntegrationTestBase {

    @Autowired InvestigationQueryService query;
    @Autowired FeedbackService feedback;
    @Autowired ProgressPublisher progress;

    @Test
    @DisplayName("TC-USR-01 Detail view contains everything the user needs")
    void detailView() {
        Long id = newInvestigation("S-1A");
        llm.script(tool("get_job_sessions", Map.of()), text("done"), answer("""
                {"summary":"Stale sessions hold memory.",
                 "findings":[{"kind":"CONFIRMED","statement":"1172 stale sessions","evidenceIds":["E1"]},
                             {"kind":"CONFIRMED","statement":"Cleanup stuck","evidenceIds":[]}],
                 "missingInformation":[],"recommendations":[{"text":"Escalate","priority":1}],
                 "proposedAction":{"name":"purge_stale_sessions","params":{},"reason":"free memory"},
                 "questionForUser":null,"resolution":"UNRESOLVED"}"""));
        orchestrator.run(id);

        InvestigationQueryService.Detail d = query.detail(id);
        assertThat(d.investigation().getSummary()).isEqualTo("Stale sessions hold memory.");
        assertThat(d.findings()).extracting(Finding::getKind).containsExactly(FindingKind.CONFIRMED, FindingKind.SUSPECTED);
        assertThat(d.findings().get(1).isDowngraded()).isTrue();
        assertThat(d.evidence()).hasSize(1);
        assertThat(d.recommendations()).extracting(Recommendation::getBody).contains("Escalate");
        assertThat(d.proposals()).hasSize(1);
        assertThat(d.events()).hasSize(1);
        assertThat(d.audit()).isNotEmpty();
    }

    @Test
    @DisplayName("TC-USR-02 Progress subscribers only receive their own investigation's events")
    void progressIsolation() {
        Long a = newInvestigation("S-1A");
        Long b = newInvestigation("S-3A");
        List<ProgressEvent> received = new ArrayList<>();
        progress.addListener(a, received::add);
        investigations.publish(a, "tool", "for A");
        investigations.publish(b, "tool", "for B");
        assertThat(received).extracting(ProgressEvent::message).containsExactly("for A");
    }

    @Test
    @DisplayName("TC-USR-03 One feedback per user; the latest wins")
    void feedbackLatestWins() {
        Long id = newInvestigation("S-1A");
        feedback.give(id, "sam", true, null);
        feedback.give(id, "sam", false, "missed the logs");
        assertThat(feedbackRepo.findByInvestigationId(id)).singleElement().satisfies(f -> {
            assertThat(f.isUseful()).isFalse();
            assertThat(f.getRemark()).isEqualTo("missed the logs");
        });
    }

    @Test
    @DisplayName("TC-USR-04 History filters by state and application")
    void historyFilters() {
        Long backup = newInvestigation("S-1A");
        Long storage = newInvestigation("S-1C");
        investigations.transition(storage, InvestigationState.INVESTIGATING, null, "test");
        investigations.transition(storage, InvestigationState.COMPLETED, null, "test");

        assertThat(query.list(InvestigationState.COMPLETED, "storage-server", null, null))
                .extracting(Investigation::getId).containsExactly(storage);
        assertThat(query.list(null, "backup-server", null, null)).extracting(Investigation::getId).containsExactly(backup);
        assertThat(query.list(null, null, Instant.parse("2030-01-01T00:00:00Z"), null)).isEmpty();
    }
}
