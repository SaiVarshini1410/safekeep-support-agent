package com.safekeep.agent.investigation;

import com.safekeep.agent.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class InvestigationLifecycleTest extends IntegrationTestBase {

    @Autowired ProgressPublisher progress;
    @Autowired StartupRecovery recovery;

    @Test
    @DisplayName("TC-INV-03 Each state transition publishes a progress event")
    void transitionsPublishProgress() {
        Long id = newInvestigation("S-1A");
        List<ProgressEvent> events = new ArrayList<>();
        progress.addListener(id, events::add);
        investigations.transition(id, InvestigationState.INVESTIGATING, null, "test");
        investigations.transition(id, InvestigationState.COMPLETED, "done", "test");
        assertThat(events).filteredOn(e -> e.type().equals("state")).hasSize(2);
        assertThat(events.get(events.size() - 1).message()).contains("INVESTIGATING -> COMPLETED");
    }

    @Test
    @DisplayName("TC-INV-02 (service) Invalid transition is rejected and audited")
    void invalidTransitionAudited() {
        Long id = newInvestigation("S-1A");
        assertThatThrownBy(() -> investigations.transition(id, InvestigationState.COMPLETED, null, "test"))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThat(auditRepo.findByInvestigationIdOrderByIdAsc(id))
                .anySatisfy(a -> assertThat(a.getType()).isEqualTo("INVALID_TRANSITION"));
        assertThat(inv(id).getState()).isEqualTo(InvestigationState.RECEIVED);
    }

    @Test
    @DisplayName("TC-INV-05 After restart, interrupted runs fail visibly and queued runs restart")
    void startupRecovery() {
        Long running1 = newInvestigation("S-1A");
        Long running2 = newInvestigation("S-3A");
        Long done = newInvestigation("S-2A");
        Long queued = newInvestigation("S-1C");
        investigations.transition(running1, InvestigationState.INVESTIGATING, null, "test");
        investigations.transition(running2, InvestigationState.INVESTIGATING, null, "test");
        investigations.transition(done, InvestigationState.INVESTIGATING, null, "test");
        investigations.transition(done, InvestigationState.COMPLETED, null, "test");

        recovery.recover();

        assertThat(inv(running1).getState()).isEqualTo(InvestigationState.UNABLE_TO_COMPLETE);
        assertThat(inv(running1).getStateReason()).isEqualTo("Interrupted by restart");
        assertThat(inv(running2).getState()).isEqualTo(InvestigationState.UNABLE_TO_COMPLETE);
        assertThat(inv(done).getState()).isEqualTo(InvestigationState.COMPLETED);
        verify(scheduler, times(2)).submit(queued);   // once at creation, once by recovery
    }
}
