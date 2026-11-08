package com.safekeep.agent.api;

import com.safekeep.agent.apps.ApplicationRegistry;
import com.safekeep.agent.audit.AuditService;
import com.safekeep.agent.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Application configuration view and runtime toggles (FR-CFG-01, FR-ACT-01). ADMIN only. */
@RestController
@RequestMapping("/api/applications")
public class AdminApiController {

    public record ToggleRequest(boolean enabled) { }

    private final ApplicationRegistry registry;
    private final AuditService audit;

    public AdminApiController(ApplicationRegistry registry, AuditService audit) {
        this.registry = registry;
        this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        registry.all().forEach(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("name", a.name());
            m.put("available", a.available());
            m.put("error", a.error());
            m.put("eventTypes", a.config().eventTypes());
            m.put("thresholds", a.config().thresholds());
            m.put("tools", a.config().tools().stream().map(t -> Map.of("name", t.name(),
                    "eventTypes", t.eventTypes(), "enabled", registry.isEnabled(a.id(), t.name()))).toList());
            m.put("actions", a.config().actions().stream().map(x -> Map.of("name", x.name(), "risk", x.risk(),
                    "requiredRole", x.requiredRole(), "enabled", registry.isEnabled(a.id(), x.name()))).toList());
            out.add(m);
        });
        return out;
    }

    @PatchMapping("/{app}/tools/{name}")
    public Map<String, Object> toggle(@PathVariable String app, @PathVariable String name, @RequestBody ToggleRequest req) {
        registry.setEnabled(app, name, req.enabled());
        audit.record(null, "CONFIG_CHANGED", CurrentUser.username(), app + "/" + name + " enabled=" + req.enabled());
        return Map.of("application", app, "name", name, "enabled", req.enabled());
    }
}
