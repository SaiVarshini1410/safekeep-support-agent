package com.safekeep.agent.api;

import com.safekeep.agent.health.HealthService;
import com.safekeep.agent.health.MetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthApiController {

    private final HealthService health;
    private final MetricsService metrics;

    public HealthApiController(HealthService health, MetricsService metrics) {
        this.health = health;
        this.metrics = metrics;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return health.health();
    }

    @GetMapping("/api/metrics")
    public Map<String, Object> metrics() {
        return metrics.metrics();
    }
}
