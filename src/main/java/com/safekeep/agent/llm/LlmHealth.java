package com.safekeep.agent.llm;

public record LlmHealth(boolean reachable, boolean modelAvailable, String model, String error) {
    public String status() {
        if (!reachable) return "DOWN";
        return modelAvailable ? "UP" : "DEGRADED";
    }
}
