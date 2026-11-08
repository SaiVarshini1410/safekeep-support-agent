package com.safekeep.agent.llm;

import java.util.Map;

public record LlmToolCall(String name, Map<String, Object> arguments) {
    public LlmToolCall {
        arguments = arguments == null ? Map.of() : arguments;
    }
}
