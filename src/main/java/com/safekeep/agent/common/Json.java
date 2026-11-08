package com.safekeep.agent.common;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.Map;

/** Thin wrapper so the rest of the code does not depend on Jackson details. */
public class Json {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public Map<String, Object> readMap(String json) {
        return mapper.readValue(json, MAP);
    }

    public Map<String, Object> readMap(InputStream in) {
        return mapper.readValue(in, MAP);
    }

    @SuppressWarnings("unchecked")
    public <T> T deepCopy(T value) {
        if (value == null) return null;
        return (T) mapper.readValue(mapper.writeValueAsString(value), Object.class);
    }
}
