package com.safekeep.agent.investigation;

/** Starts an investigation run in the background (FR-EVT-03). */
public interface RunScheduler {
    void submit(Long investigationId);

    default int activeCount() { return 0; }

    default int queueSize() { return 0; }
}
