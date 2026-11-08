package com.safekeep.agent.llm;

import java.util.List;

public record LlmResponse(String content, List<LlmToolCall> toolCalls) {
    public LlmResponse {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
