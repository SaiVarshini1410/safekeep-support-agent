package com.safekeep.agent.intake;

import java.util.Map;

/** Incoming event body (Spec Section 7.1). */
public record EventRequest(String eventId, String application, String eventType, String resource,
                           String timestamp, Map<String, Object> details) {
    public EventRequest {
        details = details == null ? Map.of() : details;
    }
}
