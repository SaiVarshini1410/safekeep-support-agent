package com.safekeep.agent.apps.config;

import java.util.List;
import java.util.Map;

public record ApplicationConfig(String name, String description, Map<String, Double> thresholds,
                                List<String> eventTypes, List<ToolConfig> tools,
                                List<ActionConfig> actions, List<String> runbooks) {
    public ApplicationConfig {
        thresholds = thresholds == null ? Map.of() : Map.copyOf(thresholds);
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        tools = tools == null ? List.of() : List.copyOf(tools);
        actions = actions == null ? List.of() : List.copyOf(actions);
        runbooks = runbooks == null ? List.of() : List.copyOf(runbooks);
    }
}