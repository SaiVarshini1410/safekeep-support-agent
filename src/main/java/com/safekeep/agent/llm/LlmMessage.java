package com.safekeep.agent.llm;

import java.util.List;

/** role: system, user, assistant or tool. */
public record LlmMessage(String role, String content, List<LlmToolCall> toolCalls, String toolName) {

    public LlmMessage {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static LlmMessage system(String c) { return new LlmMessage("system", c, null, null); }
    public static LlmMessage user(String c) { return new LlmMessage("user", c, null, null); }
    public static LlmMessage assistant(String c, List<LlmToolCall> calls) { return new LlmMessage("assistant", c, calls, null); }
    public static LlmMessage tool(String name, String c) { return new LlmMessage("tool", c, null, name); }
}
