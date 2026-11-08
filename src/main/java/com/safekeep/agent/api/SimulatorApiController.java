package com.safekeep.agent.api;

import com.safekeep.agent.intake.IntakeResult;
import com.safekeep.agent.simulator.Scenario;
import com.safekeep.agent.simulator.ScenarioCatalog;
import com.safekeep.agent.simulator.SimulatorService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/simulator")
public class SimulatorApiController {

    public record ScenarioRequest(String scenarioId) { }

    private final ScenarioCatalog catalog;
    private final SimulatorService simulator;

    public SimulatorApiController(ScenarioCatalog catalog, SimulatorService simulator) {
        this.catalog = catalog;
        this.simulator = simulator;
    }

    @GetMapping("/scenarios")
    public List<Scenario> scenarios() {
        return catalog.all();
    }

    @PostMapping("/send")
    public IntakeResult send(@RequestBody ScenarioRequest req) {
        return simulator.send(req.scenarioId());
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestBody ScenarioRequest req) {
        simulator.reset(req.scenarioId());
        return Map.of("status", "RESET", "scenarioId", req.scenarioId());
    }
}
