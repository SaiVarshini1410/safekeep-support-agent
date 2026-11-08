package com.safekeep.agent.intake;

import com.safekeep.agent.investigation.Investigation;
import com.safekeep.agent.investigation.InvestigationState;
import com.safekeep.agent.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class EventIntakeTest extends IntegrationTestBase {

    @Autowired IntakeFailureLog failureLog;

    private EventRequest memoryEvent() {
        return event("backup-server", "HIGH_MEMORY", "backup-server-01");
    }

    private IntakeException rejected(String key, EventRequest req) {
        try {
            intake.receive(key, req, null);
        } catch (IntakeException e) {
            return e;
        }
        throw new AssertionError("expected the event to be rejected");
    }

    @Test
    @DisplayName("TC-EVT-01 Valid event creates an investigation and schedules a run")
    void validEventCreates() {
        IntakeResult r = intake.receive("bk-test-key", memoryEvent(), null);
        assertThat(r.status()).isEqualTo(IntakeStatus.CREATED);
        Investigation i = inv(r.investigationId());
        assertThat(i.getState()).isEqualTo(InvestigationState.RECEIVED);
        assertThat(i.getEventCount()).isEqualTo(1);
        assertThat(i.getCorrelationKey()).isEqualTo("backup-server|HIGH_MEMORY|backup-server-01");
        assertThat(eventRepo.findByInvestigationIdOrderByReceivedAtAsc(i.getId())).hasSize(1);
        verify(scheduler, times(1)).submit(i.getId());
    }

    @ParameterizedTest(name = "key = {0}")
    @NullSource
    @ValueSource(strings = {"st-test-key", "random-key"})
    @DisplayName("TC-EVT-02 Missing, foreign or invalid API key is rejected with 401")
    void badKey(String key) {
        IntakeException e = rejected(key, memoryEvent());
        assertThat(e.getStatus()).isEqualTo(401);
        assertThat(investigationRepo.count()).isZero();
        assertThat(failureLog.list().get(0).reason()).contains("UNAUTHORIZED");
    }

    @Test
    @DisplayName("TC-EVT-03 Missing required field is rejected with 400 and the field name")
    void missingField() {
        EventRequest req = new EventRequest("evt-1", "backup-server", "HIGH_MEMORY", null, clock.instant().toString(), Map.of());
        IntakeException e = rejected("bk-test-key", req);
        assertThat(e.getStatus()).isEqualTo(400);
        assertThat(e.getFieldErrors()).contains("resource");
        assertThat(investigationRepo.count()).isZero();
    }

    @Test
    @DisplayName("TC-EVT-04 Unknown application is rejected with 422")
    void unknownApplication() {
        IntakeException e = rejected("bk-test-key", event("billing-app", "HIGH_MEMORY", "x"));
        assertThat(e.getStatus()).isEqualTo(422);
        assertThat(e.getCode()).isEqualTo("UNKNOWN_APPLICATION");
    }

    @Test
    @DisplayName("TC-EVT-05 Unknown event type is rejected with a hint listing configured types")
    void unknownEventType() {
        IntakeException e = rejected("st-test-key", event("storage-server", "DISK_FULL", "pool1"));
        assertThat(e.getStatus()).isEqualTo(422);
        assertThat(e.getCode()).isEqualTo("UNKNOWN_EVENT_TYPE");
        assertThat(e.getHint()).contains("HIGH_STORAGE_USAGE");
    }

    @Test
    @DisplayName("TC-EVT-06 Timestamp more than 24 h in the future is rejected")
    void futureTimestamp() {
        EventRequest req = new EventRequest("evt-f", "backup-server", "HIGH_MEMORY", "backup-server-01",
                clock.instant().plus(Duration.ofHours(25)).toString(), Map.of());
        assertThat(rejected("bk-test-key", req).getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("TC-EVT-07 The same eventId twice is ignored (idempotent)")
    void duplicateEventIdIgnored() {
        EventRequest req = memoryEvent();
        IntakeResult first = intake.receive("bk-test-key", req, null);
        IntakeResult second = intake.receive("bk-test-key", req, null);
        assertThat(second.status()).isEqualTo(IntakeStatus.DUPLICATE_IGNORED);
        assertThat(second.investigationId()).isEqualTo(first.investigationId());
        assertThat(inv(first.investigationId()).getEventCount()).isEqualTo(1);
        assertThat(eventRepo.count()).isEqualTo(1);
        verify(scheduler, times(1)).submit(anyLong());
    }

    @Test
    @DisplayName("TC-EVT-08 Repeated event while INVESTIGATING is linked")
    void linkedWhileInvestigating() {
        Long id = intake.receive("bk-test-key", memoryEvent(), null).investigationId();
        investigations.transition(id, InvestigationState.INVESTIGATING, null, "test");
        IntakeResult r = intake.receive("bk-test-key", memoryEvent(), null);
        assertThat(r.status()).isEqualTo(IntakeStatus.LINKED);
        assertThat(r.investigationId()).isEqualTo(id);
        assertThat(inv(id).getEventCount()).isEqualTo(2);
        assertThat(investigationRepo.count()).isEqualTo(1);
        verify(scheduler, times(1)).submit(anyLong());
    }

    @Test
    @DisplayName("TC-EVT-09 Repeated event while WAITING_FOR_INFO is linked")
    void linkedWhileWaiting() {
        Long id = intake.receive("bk-test-key", memoryEvent(), null).investigationId();
        investigations.transition(id, InvestigationState.INVESTIGATING, null, "test");
        investigations.transition(id, InvestigationState.WAITING_FOR_INFO, null, "test");
        assertThat(intake.receive("bk-test-key", memoryEvent(), null).status()).isEqualTo(IntakeStatus.LINKED);
    }

    @Test
    @DisplayName("TC-EVT-10 Repeated event after COMPLETED creates a recurrence")
    void recurrenceAfterCompleted() {
        Long first = intake.receive("bk-test-key", memoryEvent(), null).investigationId();
        investigations.transition(first, InvestigationState.INVESTIGATING, null, "test");
        investigations.transition(first, InvestigationState.COMPLETED, null, "test");
        IntakeResult r = intake.receive("bk-test-key", memoryEvent(), null);
        assertThat(r.status()).isEqualTo(IntakeStatus.CREATED);
        assertThat(inv(r.investigationId()).getRecurrenceOfId()).isEqualTo(first);
        assertThat(inv(first).getState()).isEqualTo(InvestigationState.COMPLETED);
    }

    @Test
    @DisplayName("TC-EVT-11 Different resource or event type is a different issue")
    void differentKeysAreSeparate() {
        Long a = intake.receive("bk-test-key", memoryEvent(), null).investigationId();
        Long b = intake.receive("bk-test-key", event("backup-server", "HIGH_MEMORY", "backup-server-02"), null).investigationId();
        Long c = intake.receive("bk-test-key", event("backup-server", "HIGH_CPU", "backup-server-01"), null).investigationId();
        assertThat(List.of(a, b, c)).doesNotHaveDuplicates();
        assertThat(inv(b).getRecurrenceOfId()).isNull();
        assertThat(inv(c).getRecurrenceOfId()).isNull();
    }

    @Test
    @DisplayName("TC-EVT-12 Ten simultaneous events with the same key create exactly one investigation")
    void concurrentEventsCreateOne() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IntakeResult>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return intake.receive("bk-test-key", memoryEvent(), null);
            }));
        }
        start.countDown();
        for (Future<IntakeResult> f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(investigationRepo.count()).isEqualTo(1);
        Investigation only = investigationRepo.findAll().get(0);
        assertThat(only.getEventCount()).isEqualTo(10);
        assertThat(eventRepo.count()).isEqualTo(10);
    }

    @Test
    @DisplayName("TC-EVT-13 Intake never calls the model or any tool")
    void intakeDoesNotCallLlmOrTools() {
        intake.receive("bk-test-key", memoryEvent(), "S-1A");
        assertThat(llm.requests()).isEmpty();
        assertThat(toolCallRepo.count()).isZero();
    }

    @Test
    @DisplayName("TC-EVT-14 API keys never appear in stored payloads or the failure log")
    void keysNeverStored() {
        EventRequest leaky = new EventRequest("evt-" + UUID.randomUUID(), "billing-app", "HIGH_MEMORY", "x",
                clock.instant().toString(), Map.of("token", "bk-test-key"));
        rejected("bk-test-key", leaky);
        assertThat(failureLog.list().get(0).excerpt()).doesNotContain("bk-test-key");

        EventRequest ok = new EventRequest("evt-" + UUID.randomUUID(), "backup-server", "HIGH_MEMORY", "backup-server-01",
                clock.instant().toString(), Map.of("token", "bk-test-key"));
        Long id = intake.receive("bk-test-key", ok, null).investigationId();
        assertThat(eventRepo.findByInvestigationIdOrderByReceivedAtAsc(id).get(0).getPayload()).doesNotContain("bk-test-key");
    }
}
