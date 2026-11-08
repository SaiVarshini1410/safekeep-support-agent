package com.safekeep.agent.investigation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestigationStateMachineTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("TC-INV-01 Allowed transitions succeed")
    @CsvSource({
            "RECEIVED,INVESTIGATING",
            "INVESTIGATING,WAITING_FOR_INFO",
            "INVESTIGATING,COMPLETED",
            "INVESTIGATING,UNABLE_TO_COMPLETE",
            "WAITING_FOR_INFO,INVESTIGATING",
            "COMPLETED,INVESTIGATING",
            "UNABLE_TO_COMPLETE,INVESTIGATING"
    })
    void allowedTransitions(InvestigationState from, InvestigationState to) {
        assertThatCode(() -> InvestigationStateMachine.check(from, to))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("TC-INV-02 Disallowed transitions are rejected")
    @CsvSource({
            "RECEIVED,COMPLETED",
            "RECEIVED,WAITING_FOR_INFO",
            "WAITING_FOR_INFO,COMPLETED",
            "COMPLETED,WAITING_FOR_INFO",
            "UNABLE_TO_COMPLETE,COMPLETED"
    })
    void disallowedTransitions(InvestigationState from, InvestigationState to) {
        assertThatThrownBy(() -> InvestigationStateMachine.check(from, to))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining(from + " -> " + to);
    }
}