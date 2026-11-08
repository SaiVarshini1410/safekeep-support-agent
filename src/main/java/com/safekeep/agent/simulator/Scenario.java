package com.safekeep.agent.simulator;

import java.util.Map;

public record Scenario(String id, String application, String title, String useCase, String tier,
                       String eventType, String resource, Map<String, Object> details) { }
