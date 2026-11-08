package com.safekeep.agent.simulator;

import com.safekeep.agent.apps.AdapterRegistry;
import com.safekeep.agent.apps.ApplicationRegistry;
import com.safekeep.agent.apps.RegisteredApplication;
import com.safekeep.agent.intake.EventIntakeService;
import com.safekeep.agent.intake.EventRequest;
import com.safekeep.agent.intake.IntakeResult;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/** Plays the role of the monitored applications in the demo. Uses the same intake path as real events. */
@Service
public class SimulatorService {

    private final ScenarioCatalog catalog;
    private final ApplicationRegistry registry;
    private final AdapterRegistry adapters;
    private final EventIntakeService intake;
    private final Clock clock;

    public SimulatorService(ScenarioCatalog catalog, ApplicationRegistry registry, AdapterRegistry adapters,
                            EventIntakeService intake, Clock clock) {
        this.catalog = catalog;
        this.registry = registry;
        this.adapters = adapters;
        this.intake = intake;
        this.clock = clock;
    }

    public EventRequest preview(String scenarioId) {
        Scenario s = catalog.get(scenarioId);
        return new EventRequest("evt-" + UUID.randomUUID().toString().substring(0, 8), s.application(),
                s.eventType(), s.resource(), clock.instant().toString(), s.details());
    }

    public IntakeResult send(String scenarioId) {
        Scenario s = catalog.get(scenarioId);
        String key = registry.find(s.application()).map(RegisteredApplication::apiKey).orElse("");
        return intake.receive(key, preview(scenarioId), scenarioId);
    }

    public void reset(String scenarioId) {
        Scenario s = catalog.get(scenarioId);
        adapters.get(s.application()).ifPresent(a -> a.reset(scenarioId));
    }
}
