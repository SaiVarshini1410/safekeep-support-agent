package com.safekeep.agent.apps.config;

import com.safekeep.agent.security.Role;

import java.util.List;

public record ActionConfig(String name, String description, String risk, Role requiredRole,
                           List<ParamConfig> parameters, VerifyConfig verify, String instructions,
                           List<String> eventTypes) {
    public ActionConfig {
        risk = risk == null ? "LOW" : risk;
        requiredRole = requiredRole == null ? Role.SUPPORT_ENGINEER : requiredRole;  // decision D3 default
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        eventTypes = eventTypes == null ? List.of("*") : List.copyOf(eventTypes);
    }

    /** An action only fits the event types it was designed for (decision D22). */
    public boolean appliesTo(String eventType) {
        return eventTypes.contains("*") || eventTypes.contains(eventType);
    }
}
