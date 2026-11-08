package com.safekeep.agent.simulator;

import com.safekeep.agent.common.Json;
import com.safekeep.agent.common.NotFoundException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ScenarioCatalog {

    private final List<Scenario> scenarios = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public ScenarioCatalog(Json json) throws IOException {
        try (InputStream in = new ClassPathResource("scenarios/catalog.json").getInputStream()) {
            List<Object> list = (List<Object>) json.readMap(in).get("scenarios");
            for (Object o : list) {
                Map<String, Object> m = (Map<String, Object>) o;
                scenarios.add(new Scenario((String) m.get("id"), (String) m.get("application"), (String) m.get("title"),
                        (String) m.get("useCase"), (String) m.get("tier"), (String) m.get("eventType"),
                        (String) m.get("resource"), (Map<String, Object>) m.getOrDefault("details", Map.of())));
            }
        }
    }

    public List<Scenario> all() {
        return List.copyOf(scenarios);
    }

    public Scenario get(String id) {
        return scenarios.stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Scenario " + id + " not found"));
    }
}
