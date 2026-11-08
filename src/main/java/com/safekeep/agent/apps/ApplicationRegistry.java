package com.safekeep.agent.apps;

import com.safekeep.agent.apps.config.ActionConfig;
import com.safekeep.agent.apps.config.ApplicationConfig;
import com.safekeep.agent.apps.config.ToolConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Loaded application configuration plus runtime enable/disable toggles (FR-CFG-01, FR-ACT-01). */
public class ApplicationRegistry {

    private final Map<String, RegisteredApplication> apps = new LinkedHashMap<>();
    private final Set<String> disabled = ConcurrentHashMap.newKeySet();

    public ApplicationRegistry(Map<String, ApplicationConfig> configs, Map<String, String> apiKeys) {
        new TreeMap<>(configs).forEach((id, cfg) -> {
            validate(id, cfg);
            String key = apiKeys.getOrDefault(id, "");
            boolean available = key != null && !key.isBlank();
            apps.put(id, new RegisteredApplication(id, cfg, key, available,
                    available ? null : "API key not configured (environment variable missing)"));
        });
    }

    private static void validate(String id, ApplicationConfig cfg) {
        Set<String> toolNames = new HashSet<>();
        for (ToolConfig t : cfg.tools()) {
            if (!toolNames.add(t.name())) {
                throw new IllegalStateException("Application '" + id + "': duplicate tool '" + t.name() + "'");
            }
        }
        for (ActionConfig a : cfg.actions()) {
            if (a.verify() == null || a.verify().tool() == null || a.verify().condition() == null) {
                throw new IllegalStateException("Application '" + id + "': action '" + a.name()
                        + "' has no verify configuration");
            }
            if (!toolNames.contains(a.verify().tool())) {
                throw new IllegalStateException("Application '" + id + "': action '" + a.name()
                        + "' verifies with unknown tool '" + a.verify().tool() + "'");
            }
        }
    }

    public Optional<RegisteredApplication> find(String id) {
        return Optional.ofNullable(id == null ? null : apps.get(id));
    }

    public List<RegisteredApplication> all() {
        return List.copyOf(apps.values());
    }

    public Optional<RegisteredApplication> findByApiKey(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        byte[] given = key.getBytes(StandardCharsets.UTF_8);
        return apps.values().stream()
                .filter(a -> a.available()
                        && MessageDigest.isEqual(given, a.apiKey().getBytes(StandardCharsets.UTF_8)))
                .findFirst();
    }

    public Collection<String> allApiKeys() {
        return apps.values().stream().map(RegisteredApplication::apiKey).filter(k -> k != null && !k.isBlank()).toList();
    }

    public boolean isEnabled(String appId, String name) {
        return !disabled.contains(appId + "/" + name);
    }

    public void setEnabled(String appId, String name, boolean enabled) {
        RegisteredApplication app = find(appId).orElseThrow(() -> new IllegalArgumentException("Unknown application " + appId));
        if (app.tool(name).isEmpty() && app.action(name).isEmpty()) {
            throw new IllegalArgumentException("Unknown tool or action '" + name + "' for " + appId);
        }
        if (enabled) disabled.remove(appId + "/" + name);
        else disabled.add(appId + "/" + name);
    }

    /** Read tools offered to the LLM: configured for the event type and enabled (FR-AI-02). */
    public List<ToolConfig> readToolsFor(String appId, String eventType) {
        return find(appId).map(a -> a.config().tools().stream()
                        .filter(t -> t.appliesTo(eventType) && isEnabled(appId, t.name()))
                        .toList())
                .orElse(List.of());
    }

    public List<ActionConfig> enabledActions(String appId) {
        return find(appId).map(a -> a.config().actions().stream()
                        .filter(x -> isEnabled(appId, x.name())).toList())
                .orElse(List.of());
    }
}
