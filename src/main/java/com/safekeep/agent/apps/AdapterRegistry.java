package com.safekeep.agent.apps;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AdapterRegistry {

    private final Map<String, AppAdapter> adapters;

    public AdapterRegistry(List<AppAdapter> adapters) {
        this.adapters = adapters.stream().collect(Collectors.toMap(AppAdapter::applicationId, Function.identity()));
    }

    public Optional<AppAdapter> get(String applicationId) {
        return Optional.ofNullable(adapters.get(applicationId));
    }
}
