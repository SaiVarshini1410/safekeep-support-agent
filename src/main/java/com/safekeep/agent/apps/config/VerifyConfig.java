package com.safekeep.agent.apps.config;

import java.util.Map;

/**
 * How to verify an action (decision D8): run a read tool, then evaluate conditions on its result.
 * condition true -> RESOLVED; else partialCondition true -> PARTIALLY_RESOLVED; else FAILED.
 */
public record VerifyConfig(String tool, Map<String, String> args, String condition, String partialCondition) {
    public VerifyConfig {
        args = args == null ? Map.of() : Map.copyOf(args);
    }
}
