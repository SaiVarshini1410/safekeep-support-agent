package com.safekeep.agent.llm;

import java.util.Map;

/** A tool offered to the model, with a JSON-schema description of its parameters. */
public record LlmToolSpec(String name, String description, Map<String, Object> parameters) { }
