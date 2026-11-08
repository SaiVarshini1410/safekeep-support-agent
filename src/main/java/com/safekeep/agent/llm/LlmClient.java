package com.safekeep.agent.llm;

/** The only way the Agent talks to a model (decision D14). */
public interface LlmClient {

    LlmResponse chat(LlmRequest request);

    LlmHealth health();
}
