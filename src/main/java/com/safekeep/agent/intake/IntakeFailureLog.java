package com.safekeep.agent.intake;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Latest rejected events, newest first, shown on the Health screen (FR-EVT-05). */
@Component
public class IntakeFailureLog {

    public static final int MAX = 50;

    private final Deque<IntakeFailure> failures = new ArrayDeque<>();
    private final Clock clock;

    public IntakeFailureLog(Clock clock) {
        this.clock = clock;
    }

    public synchronized void record(String application, String reason, String excerpt) {
        String ex = excerpt == null ? "" : (excerpt.length() > 300 ? excerpt.substring(0, 297) + "..." : excerpt);
        failures.addFirst(new IntakeFailure(clock.instant(), application, reason, ex));
        while (failures.size() > MAX) failures.removeLast();
    }

    public synchronized List<IntakeFailure> list() {
        return List.copyOf(failures);
    }
}
