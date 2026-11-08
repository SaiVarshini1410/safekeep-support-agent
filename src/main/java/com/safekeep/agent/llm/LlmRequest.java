package com.safekeep.agent.llm;

import java.util.List;

public record LlmRequest(List<LlmMessage> messages, List<LlmToolSpec> tools, boolean jsonFormat) {
    public LlmRequest {
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
