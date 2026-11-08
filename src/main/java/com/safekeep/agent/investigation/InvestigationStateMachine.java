package com.safekeep.agent.investigation;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.safekeep.agent.investigation.InvestigationState.*;

/** The only place that decides which state changes are allowed (Spec Section 6). */
public final class InvestigationStateMachine {

    private static final Map<InvestigationState, Set<InvestigationState>> ALLOWED = Map.of(
            RECEIVED,           EnumSet.of(INVESTIGATING),
            INVESTIGATING,      EnumSet.of(WAITING_FOR_INFO, COMPLETED, UNABLE_TO_COMPLETE),
            WAITING_FOR_INFO,   EnumSet.of(INVESTIGATING),
            COMPLETED,          EnumSet.of(INVESTIGATING),
            UNABLE_TO_COMPLETE, EnumSet.of(INVESTIGATING)
    );

    private InvestigationStateMachine() { }

    public static boolean canTransition(InvestigationState from, InvestigationState to) {
        return ALLOWED.get(from).contains(to);
    }

    public static void check(InvestigationState from, InvestigationState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateTransitionException(from, to);
        }
    }
}
