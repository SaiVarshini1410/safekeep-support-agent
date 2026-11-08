package com.safekeep.agent.investigation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Instant;

import static com.safekeep.agent.investigation.InvestigationState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestigationTest {

    private static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");
    private static final CorrelationKey KEY =
            new CorrelationKey("backup-server", "HIGH_MEMORY", "backup-server-01");

    @Test
    @DisplayName("TC-INV-06 New investigation starts RECEIVED/UNKNOWN; transitions do not change resolution")
    void newInvestigationDefaultsAndResolutionIndependence() {
        Investigation inv = Investigation.open(KEY, T0);

        assertThat(inv.getState()).isEqualTo(RECEIVED);
        assertThat(inv.getResolution()).isEqualTo(Resolution.UNKNOWN);
        assertThat(inv.getEventCount()).isEqualTo(1);
        assertThat(inv.getCorrelationKey()).isEqualTo("backup-server|HIGH_MEMORY|backup-server-01");

        inv.transitionTo(INVESTIGATING, null, T0.plusSeconds(1));
        inv.transitionTo(COMPLETED, "Findings produced", T0.plusSeconds(30));

        assertThat(inv.getState()).isEqualTo(COMPLETED);
        assertThat(inv.getResolution()).isEqualTo(Resolution.UNKNOWN);
        assertThat(inv.getCompletedAt()).isEqualTo(T0.plusSeconds(30));
    }

    @Test
    @DisplayName("TC-INV-02 Entity rejects a disallowed transition and keeps its state")
    void entityRejectsDisallowedTransition() {
        Investigation inv = Investigation.open(KEY, T0);

        assertThatThrownBy(() -> inv.transitionTo(COMPLETED, "skip", T0))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThat(inv.getState()).isEqualTo(RECEIVED);
    }
}