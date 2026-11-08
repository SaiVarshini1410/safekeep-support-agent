package com.safekeep.agent.support;

import com.safekeep.agent.llm.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scripted stand-in for the model (Test Plan Section 2.2). Returns queued responses in order
 * and records every request so tests can assert what the model was sent.
 */
public class FakeLlmClient implements LlmClient {

    public static final String DEFAULT_FINAL = """
            {"summary":"Default summary","findings":[],"missingInformation":["n/a"],
             "recommendations":[],"proposedAction":null,"questionForUser":null,"resolution":"UNKNOWN"}""";

    private final Deque<Object> script = new ArrayDeque<>();
    private final Map<String, Deque<Object>> keyed = new ConcurrentHashMap<>();
    private final List<LlmRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean failWhenEmpty;

    public static LlmResponse tool(String name, Map<String, Object> args) {
        return new LlmResponse("", List.of(new LlmToolCall(name, args)));
    }

    public static LlmResponse text(String content) {
        return new LlmResponse(content, List.of());
    }

    public static LlmResponse answer(String json) {
        return new LlmResponse(json, List.of());
    }

    public static RuntimeException unavailable() {
        return new LlmUnavailableException("connection refused", null);
    }

    public synchronized void reset() {
        script.clear();
        keyed.clear();
        requests.clear();
        failWhenEmpty = false;
    }

    public synchronized FakeLlmClient script(Object... items) {
        script.addAll(Arrays.asList(items));
        return this;
    }

    /** Responses used only for requests whose messages contain the marker text. */
    public synchronized FakeLlmClient scriptFor(String marker, Object... items) {
        keyed.computeIfAbsent(marker, k -> new ArrayDeque<>()).addAll(Arrays.asList(items));
        return this;
    }

    public void failWhenEmpty(boolean value) {
        this.failWhenEmpty = value;
    }

    public List<LlmRequest> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public boolean anyRequestContains(String text) {
        return requests().stream().flatMap(r -> r.messages().stream()).anyMatch(m -> m.content().contains(text));
    }

    @Override
    public synchronized LlmResponse chat(LlmRequest request) {
        requests.add(request);
        Deque<Object> queue = script;
        for (Map.Entry<String, Deque<Object>> e : keyed.entrySet()) {
            if (request.messages().stream().anyMatch(m -> m.content().contains(e.getKey()))) {
                queue = e.getValue();
                break;
            }
        }
        Object next = queue.poll();
        if (next == null) {
            if (failWhenEmpty) throw unavailable();
            return request.jsonFormat() ? answer(DEFAULT_FINAL) : text("done");
        }
        if (next instanceof RuntimeException ex) throw ex;
        return (LlmResponse) next;
    }

    @Override
    public LlmHealth health() {
        return new LlmHealth(true, true, "fake-model", null);
    }
}
