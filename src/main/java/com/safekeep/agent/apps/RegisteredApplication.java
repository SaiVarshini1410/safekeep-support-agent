package com.safekeep.agent.apps;

import com.safekeep.agent.apps.config.ActionConfig;
import com.safekeep.agent.apps.config.ApplicationConfig;
import com.safekeep.agent.apps.config.ToolConfig;

import java.util.Optional;

public record RegisteredApplication(String id, ApplicationConfig config, String apiKey,
                                    boolean available, String error) {

    public String name() { return config.name(); }

    public boolean hasEventType(String eventType) { return config.eventTypes().contains(eventType); }

    public Optional<ToolConfig> tool(String name) {
        return config.tools().stream().filter(t -> t.name().equals(name)).findFirst();
    }

    public Optional<ActionConfig> action(String name) {
        return config.actions().stream().filter(a -> a.name().equals(name)).findFirst();
    }

    /** Never print the API key (NFR-SEC-01). */
    @Override
    public String toString() {
        return "RegisteredApplication[" + id + ", available=" + available + "]";
    }
}
