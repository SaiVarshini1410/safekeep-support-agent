package com.safekeep.agent.investigation;

public enum InvestigationState {
    RECEIVED,
    INVESTIGATING,
    WAITING_FOR_INFO,
    COMPLETED,
    UNABLE_TO_COMPLETE;

    /** Open = the Agent may still work on it. Used later for duplicate linking (D1). */
    public boolean isOpen() {
        return this != COMPLETED && this != UNABLE_TO_COMPLETE;
    }
}