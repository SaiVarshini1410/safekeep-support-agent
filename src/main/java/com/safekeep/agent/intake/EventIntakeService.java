package com.safekeep.agent.intake;

import com.safekeep.agent.apps.ApplicationRegistry;
import com.safekeep.agent.apps.RegisteredApplication;
import com.safekeep.agent.audit.AuditService;
import com.safekeep.agent.common.Json;
import com.safekeep.agent.common.Masking;
import com.safekeep.agent.investigation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Validation, idempotency, correlation and create-or-link (Spec Section 7). Never calls the LLM. */
@Service
public class EventIntakeService {

    private static final List<InvestigationState> OPEN = Arrays.stream(InvestigationState.values())
            .filter(InvestigationState::isOpen).toList();
    private static final List<InvestigationState> CLOSED = Arrays.stream(InvestigationState.values())
            .filter(s -> !s.isOpen()).toList();

    private final ApplicationRegistry registry;
    private final InvestigationRepository investigations;
    private final EventRecordRepository events;
    private final InvestigationService investigationService;
    private final AuditService audit;
    private final RunScheduler scheduler;
    private final IntakeFailureLog failureLog;
    private final TransactionTemplate tx;
    private final Json json;
    private final Clock clock;
    private final Map<String, Object> keyLocks = new ConcurrentHashMap<>();

    public EventIntakeService(ApplicationRegistry registry, InvestigationRepository investigations,
                              EventRecordRepository events, InvestigationService investigationService,
                              AuditService audit, RunScheduler scheduler, IntakeFailureLog failureLog,
                              TransactionTemplate tx, Json json, Clock clock) {
        this.registry = registry;
        this.investigations = investigations;
        this.events = events;
        this.investigationService = investigationService;
        this.audit = audit;
        this.scheduler = scheduler;
        this.failureLog = failureLog;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    public IntakeResult receive(String apiKey, EventRequest req, String scenarioId) {
        Instant now = clock.instant();

        // V-E1: the key must belong to some configured application
        Optional<RegisteredApplication> keyOwner = registry.findByApiKey(apiKey);
        if (keyOwner.isEmpty()) {
            throw fail(401, "UNAUTHORIZED", "Missing or invalid API key", null, null, req, apiKey);
        }
        // V-E2: required fields
        List<String> missing = new ArrayList<>();
        if (req == null) {
            missing.add("body");
        } else {
            if (blank(req.eventId())) missing.add("eventId");
            if (blank(req.application())) missing.add("application");
            if (blank(req.eventType())) missing.add("eventType");
            if (blank(req.resource())) missing.add("resource");
            if (blank(req.timestamp())) missing.add("timestamp");
        }
        if (!missing.isEmpty()) {
            throw fail(400, "INVALID_EVENT", "Missing required fields: " + String.join(", ", missing),
                    null, missing, req, apiKey);
        }
        // V-E3: application configured
        RegisteredApplication app = registry.find(req.application()).orElseThrow(() ->
                fail(422, "UNKNOWN_APPLICATION", "Application " + req.application() + " is not configured",
                        "Configured: " + registry.all().stream().map(RegisteredApplication::id).toList(), null, req, apiKey));
        // V-E1 (cont.): key must belong to this application
        if (!keyOwner.get().id().equals(app.id())) {
            throw fail(401, "UNAUTHORIZED", "API key does not belong to application " + app.id(), null, null, req, apiKey);
        }
        // V-E4: event type configured
        if (!app.hasEventType(req.eventType())) {
            throw fail(422, "UNKNOWN_EVENT_TYPE", "Event type " + req.eventType()
                            + " is not configured for application " + app.id(),
                    "Configured: " + String.join(", ", app.config().eventTypes()), null, req, apiKey);
        }
        // V-E5: timestamp sane
        Instant ts;
        try {
            ts = Instant.parse(req.timestamp());
        } catch (DateTimeParseException e) {
            throw fail(400, "INVALID_EVENT", "timestamp must be ISO-8601, e.g. 2026-11-07T10:00:00Z",
                    null, List.of("timestamp"), req, apiKey);
        }
        if (ts.isAfter(now.plus(Duration.ofHours(24)))) {
            throw fail(400, "INVALID_EVENT", "timestamp is more than 24 hours in the future",
                    null, List.of("timestamp"), req, apiKey);
        }

        CorrelationKey key = new CorrelationKey(app.id(), req.eventType(), req.resource());
        String payload = Masking.mask(json.write(req), withKey(apiKey));
        IntakeResult result;
        synchronized (keyLocks.computeIfAbsent(key.value(), k -> new Object())) {
            result = tx.execute(status -> createOrLink(key, req, payload, scenarioId, now));
        }
        if (result != null && result.status() == IntakeStatus.CREATED) {
            scheduler.submit(result.investigationId());
        }
        return result;
    }

    private IntakeResult createOrLink(CorrelationKey key, EventRequest req, String payload, String scenarioId, Instant now) {
        Optional<EventRecord> seen = events.findByEventId(req.eventId());
        if (seen.isPresent()) {
            Long id = seen.get().getInvestigationId();
            return new IntakeResult(IntakeStatus.DUPLICATE_IGNORED, id, "INV-" + id);
        }
        Optional<Investigation> open = investigations.findFirstByCorrelationKeyAndStateInOrderByCreatedAtDesc(key.value(), OPEN);
        if (open.isPresent()) {
            Long id = open.get().getId();
            Investigation inv = investigationService.mutate(id, i -> i.linkEvent(now));
            events.save(new EventRecord(id, req.eventId(), now, payload));
            audit.record(id, "EVENT_LINKED", key.applicationId(), "Event " + req.eventId() + " linked (count " + inv.getEventCount() + ")");
            investigationService.publish(id, "event", "Repeated event linked (count " + inv.getEventCount() + ")");
            return new IntakeResult(IntakeStatus.LINKED, id, inv.displayId());
        }
        Investigation inv = Investigation.open(key, now);
        inv.assignScenario(scenarioId);
        investigations.findFirstByCorrelationKeyAndStateInOrderByCreatedAtDesc(key.value(), CLOSED)
                .ifPresent(prev -> inv.markRecurrenceOf(prev.getId()));
        Investigation saved = investigations.save(inv);
        events.save(new EventRecord(saved.getId(), req.eventId(), now, payload));
        audit.record(saved.getId(), "INVESTIGATION_CREATED", key.applicationId(),
                "Event " + req.eventId() + " " + key.eventType() + " on " + key.resource()
                        + (saved.getRecurrenceOfId() == null ? "" : " (recurrence of INV-" + saved.getRecurrenceOfId() + ")"));
        return new IntakeResult(IntakeStatus.CREATED, saved.getId(), saved.displayId());
    }

    private IntakeException fail(int status, String code, String message, String hint, List<String> fields,
                                 EventRequest req, String apiKey) {
        String app = req == null ? null : req.application();
        String excerpt = req == null ? "" : Masking.mask(json.write(req), withKey(apiKey));
        failureLog.record(app, code + ": " + message, excerpt);
        return new IntakeException(status, code, message, hint, fields);
    }

    private List<String> withKey(String apiKey) {
        List<String> secrets = new ArrayList<>(registry.allApiKeys());
        if (apiKey != null) secrets.add(apiKey);
        return secrets;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
