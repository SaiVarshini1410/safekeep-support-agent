package com.safekeep.agent.investigation;

import com.safekeep.agent.common.Json;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Pushes progress events to browsers (Server-Sent Events), keyed by investigation (NFR-SCAL-01). */
@Component
public class ProgressPublisher {

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<Long, List<Consumer<ProgressEvent>>> listeners = new ConcurrentHashMap<>();
    private final Json json;

    public ProgressPublisher(Json json) {
        this.json = json;
    }

    public SseEmitter subscribe(Long investigationId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        List<SseEmitter> list = emitters.computeIfAbsent(investigationId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(e -> list.remove(emitter));
        return emitter;
    }

    /** Used by tests and internal observers. */
    public void addListener(Long investigationId, Consumer<ProgressEvent> listener) {
        listeners.computeIfAbsent(investigationId, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void publish(ProgressEvent event) {
        for (SseEmitter e : emitters.getOrDefault(event.investigationId(), List.of())) {
            try {
                e.send(SseEmitter.event().name(event.type()).data(json.write(event)));
            } catch (IOException | IllegalStateException ex) {
                emitters.getOrDefault(event.investigationId(), List.of()).remove(e);
            }
        }
        listeners.getOrDefault(event.investigationId(), List.of()).forEach(l -> l.accept(event));
    }
}
