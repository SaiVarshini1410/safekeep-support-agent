package com.safekeep.agent.support;

import com.safekeep.agent.apps.AppAdapter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@TestConfiguration
public class TestBeans {

    @Bean
    @Primary
    public FakeLlmClient fakeLlmClient() {
        return new FakeLlmClient();
    }

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock();
    }

    /** Adapter for the test-only application (TC-CFG-04). Status is BROKEN until fix_it runs. */
    @Bean
    public AppAdapter testAppAdapter() {
        return new AppAdapter() {
            private final Map<String, String> status = new ConcurrentHashMap<>();

            @Override public String applicationId() { return "test-app"; }

            @Override public Map<String, Object> executeTool(String s, String tool, Map<String, Object> args) {
                return Map.of("status", status.getOrDefault(String.valueOf(s), "BROKEN"));
            }

            @Override public Map<String, Object> executeAction(String s, String action, Map<String, Object> args) {
                status.put(String.valueOf(s), "OK");
                return Map.of("status", "OK");
            }

            @Override public void reset(String s) { status.remove(String.valueOf(s)); }
        };
    }
}
