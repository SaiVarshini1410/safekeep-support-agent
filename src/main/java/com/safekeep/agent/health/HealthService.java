package com.safekeep.agent.health;

import com.safekeep.agent.apps.ApplicationRegistry;
import com.safekeep.agent.intake.IntakeFailureLog;
import com.safekeep.agent.investigation.AsyncRunScheduler;
import com.safekeep.agent.investigation.Investigation;
import com.safekeep.agent.investigation.InvestigationRepository;
import com.safekeep.agent.investigation.InvestigationState;
import com.safekeep.agent.llm.LlmClient;
import org.springframework.stereotype.Service;

import java.util.*;

/** Agent health, connection status and integration errors (FR-CFG-03). */
@Service
public class HealthService {

    private final LlmClient llm;
    private final ApplicationRegistry registry;
    private final InvestigationRepository investigations;
    private final IntakeFailureLog failures;
    private final AsyncRunScheduler scheduler;

    public HealthService(LlmClient llm, ApplicationRegistry registry, InvestigationRepository investigations,
                         IntakeFailureLog failures, AsyncRunScheduler scheduler) {
        this.llm = llm;
        this.registry = registry;
        this.investigations = investigations;
        this.failures = failures;
        this.scheduler = scheduler;
    }

    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("llm", llm.health());
        List<Investigation> all = investigations.findAll();
        List<Map<String, Object>> apps = new ArrayList<>();
        registry.all().forEach(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("name", a.name());
            m.put("status", a.available() ? "CONNECTED" : "UNAVAILABLE");
            m.put("error", a.error());
            m.put("lastEventAt", all.stream().filter(i -> i.getApplicationId().equals(a.id()))
                    .map(Investigation::getUpdatedAt).max(Comparator.naturalOrder()).orElse(null));
            apps.add(m);
        });
        out.put("applications", apps);
        Map<String, Long> byState = new LinkedHashMap<>();
        for (InvestigationState s : InvestigationState.values()) {
            byState.put(s.name(), all.stream().filter(i -> i.getState() == s).count());
        }
        out.put("investigationsByState", byState);
        out.put("workers", Map.of("active", scheduler.activeCount(), "queued", scheduler.queueSize()));
        out.put("intakeErrors", failures.list());
        return out;
    }
}
