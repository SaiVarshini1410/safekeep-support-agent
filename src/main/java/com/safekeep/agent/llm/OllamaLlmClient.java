package com.safekeep.agent.llm;

import com.safekeep.agent.common.Json;
import com.safekeep.agent.config.SafekeepProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/** Calls Ollama's /api/chat (non-streaming) with tool definitions. */
@Component
public class OllamaLlmClient implements LlmClient {

    private final RestClient rest;
    private final Json json;
    private final SafekeepProperties.Agent cfg;

    public OllamaLlmClient(SafekeepProperties props, Json json) {
        this.cfg = props.agent();
        this.json = json;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(cfg.llmTimeoutSeconds()));
        this.rest = RestClient.builder().baseUrl(cfg.ollamaUrl()).requestFactory(factory).build();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("stream", false);
        body.put("options", Map.of("temperature", cfg.temperature()));
        body.put("messages", request.messages().stream().map(this::toOllama).toList());
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(t -> Map.of("type", "function",
                    "function", Map.of("name", t.name(), "description", t.description(),
                            "parameters", t.parameters()))).toList());
        }
        if (request.jsonFormat()) {
            body.put("format", "json");
        }
        String raw;
        try {
            raw = rest.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                    .body(json.write(body)).retrieve().body(String.class);
        } catch (RestClientException e) {
            throw new LlmUnavailableException("AI service unavailable: " + e.getMessage(), e);
        }
        return parse(raw);
    }

    @SuppressWarnings("unchecked")
    private LlmResponse parse(String raw) {
        Map<String, Object> m = json.readMap(raw);
        Map<String, Object> msg = (Map<String, Object>) m.getOrDefault("message", Map.of());
        String content = String.valueOf(msg.getOrDefault("content", ""));
        List<LlmToolCall> calls = new ArrayList<>();
        if (msg.get("tool_calls") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> tc) || !(tc.get("function") instanceof Map<?, ?> fn)) continue;
                Object args = fn.get("arguments");
                Map<String, Object> argMap;
                if (args instanceof Map<?, ?> am) {
                    argMap = (Map<String, Object>) am;
                } else if (args instanceof String s && !s.isBlank()) {
                    try { argMap = json.readMap(s); } catch (RuntimeException e) { argMap = Map.of(); }
                } else {
                    argMap = Map.of();
                }
                calls.add(new LlmToolCall(String.valueOf(fn.get("name")), argMap));
            }
        }
        return new LlmResponse(content, calls);
    }

    private Map<String, Object> toOllama(LlmMessage m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", m.role());
        out.put("content", m.content());
        if (!m.toolCalls().isEmpty()) {
            out.put("tool_calls", m.toolCalls().stream()
                    .map(c -> Map.of("function", Map.of("name", c.name(), "arguments", c.arguments()))).toList());
        }
        if (m.toolName() != null) {
            out.put("tool_name", m.toolName());
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public LlmHealth health() {
        try {
            String raw = rest.get().uri("/api/tags").retrieve().body(String.class);
            List<Object> models = (List<Object>) json.readMap(raw).getOrDefault("models", List.of());
            boolean present = models.stream().anyMatch(o -> o instanceof Map<?, ?> mm
                    && String.valueOf(mm.get("name")).startsWith(cfg.model()));
            return new LlmHealth(true, present, cfg.model(), present ? null : "Model not pulled: run 'ollama pull " + cfg.model() + "'");
        } catch (RuntimeException e) {
            return new LlmHealth(false, false, cfg.model(), "Ollama not reachable at " + cfg.ollamaUrl());
        }
    }
}
