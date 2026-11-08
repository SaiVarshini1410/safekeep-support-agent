package com.safekeep.agent.investigation;

/** application + event type + resource. Equal keys = same issue (decision D1). */
public record CorrelationKey(String applicationId, String eventType, String resource) {

    public String value() {
        return applicationId + "|" + eventType + "|" + resource;
    }
}
