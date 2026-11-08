package com.safekeep.agent.apps.config;

import java.util.List;

public record ParamConfig(String name, String type, List<String> allowed,
                          Integer min, Integer max, String defaultValue, boolean required) {
    public ParamConfig {
        allowed = allowed == null ? List.of() : List.copyOf(allowed);
    }
}