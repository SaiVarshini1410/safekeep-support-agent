package com.safekeep.agent.config;

import com.safekeep.agent.apps.config.ApplicationConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "safekeep")
public record SafekeepProperties(Map<String, ApplicationConfig> applications,
                                 Map<String, String> apiKeys,
                                 Agent agent) {

    public SafekeepProperties {
        applications = applications == null ? Map.of() : Map.copyOf(applications);
        apiKeys = apiKeys == null ? Map.of() : new HashMap<>(apiKeys);
        agent = agent == null ? Agent.defaults() : agent;
    }

    /** Agent limits (Spec Section 8.6). Missing values fall back to defaults. */
    public record Agent(String ollamaUrl, String model, Double temperature, Integer maxToolCalls,
                        Integer llmTimeoutSeconds, Integer llmRetries, Long retryBackoffMs,
                        Integer toolTimeoutSeconds, Integer maxToolResultChars,
                        Integer proposalTtlMinutes, Integer workerThreads, Integer minToolCalls) {
        public Agent {
            ollamaUrl = ollamaUrl == null ? "http://localhost:11434" : ollamaUrl;
            model = model == null ? "qwen2.5:3b" : model;
            temperature = temperature == null ? 0.1 : temperature;
            maxToolCalls = maxToolCalls == null ? 8 : maxToolCalls;
            llmTimeoutSeconds = llmTimeoutSeconds == null ? 60 : llmTimeoutSeconds;
            llmRetries = llmRetries == null ? 2 : llmRetries;
            retryBackoffMs = retryBackoffMs == null ? 1000L : retryBackoffMs;
            toolTimeoutSeconds = toolTimeoutSeconds == null ? 5 : toolTimeoutSeconds;
            maxToolResultChars = maxToolResultChars == null ? 1500 : maxToolResultChars;
            proposalTtlMinutes = proposalTtlMinutes == null ? 15 : proposalTtlMinutes;
            workerThreads = workerThreads == null ? 4 : workerThreads;
            minToolCalls = minToolCalls == null ? 3 : minToolCalls;
        }

        public static Agent defaults() {
            return new Agent(null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }
}
