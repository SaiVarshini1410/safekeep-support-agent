package com.safekeep.agent.investigation;

/** Runs the agent for one investigation. Implemented by the AgentOrchestrator. */
public interface InvestigationRunner {
    void run(Long investigationId);
}
