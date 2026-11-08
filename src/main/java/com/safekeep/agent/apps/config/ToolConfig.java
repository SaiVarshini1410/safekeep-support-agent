package com.safekeep.agent.apps.config;

import java.util.List;

public record ToolConfig(String name, String description,
                         List<String> eventTypes, List<ParamConfig> parameters) {
    public ToolConfig {
        eventTypes = eventTypes == null ? List.of("*") : List.copyOf(eventTypes);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    public boolean appliesTo(String eventType) {
        return eventTypes.contains("*") || eventTypes.contains(eventType);
    }
}
