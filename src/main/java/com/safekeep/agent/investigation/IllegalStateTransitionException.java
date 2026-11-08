package com.safekeep.agent.investigation;

public class IllegalStateTransitionException extends RuntimeException {
    public IllegalStateTransitionException(InvestigationState from, InvestigationState to) {
        super("Transition not allowed: " + from + " -> " + to);
    }
}