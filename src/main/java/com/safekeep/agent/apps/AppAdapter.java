package com.safekeep.agent.apps;

import java.util.Map;

/** Application-specific integration (NFR-MAINT-01). A real system would implement this over HTTP. */
public interface AppAdapter {

    String applicationId();

    Map<String, Object> executeTool(String scenarioId, String tool, Map<String, Object> args);

    Map<String, Object> executeAction(String scenarioId, String action, Map<String, Object> args);

    void reset(String scenarioId);
}
