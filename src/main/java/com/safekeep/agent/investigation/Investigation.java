package com.safekeep.agent.investigation;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Entity
public class Investigation {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "investigation_seq")
    @SequenceGenerator(name = "investigation_seq", sequenceName = "investigation_seq",
                       initialValue = 101, allocationSize = 1)
    private Long id;

    @Column(nullable = false) private String applicationId;
    @Column(nullable = false) private String eventType;
    @Column(nullable = false) private String resource;
    @Column(nullable = false) private String correlationKey;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private InvestigationState state;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Resolution resolution;

    @Column(length = 1000) private String stateReason;
    @Column(length = 4000) private String summary;
    @Column(length = 4000) private String missingInformation;

    private int eventCount;
    private Long recurrenceOfId;
    private int toolCallsUsed;
    private String scenarioId;

    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    private Instant firstRecommendationAt;
    private Instant completedAt;

    protected Investigation() { }   // required by JPA; not for our code

    /** The only way to create an investigation: always starts RECEIVED / UNKNOWN. */
    public static Investigation open(CorrelationKey key, Instant now) {
        Investigation inv = new Investigation();
        inv.applicationId = key.applicationId();
        inv.eventType = key.eventType();
        inv.resource = key.resource();
        inv.correlationKey = key.value();
        inv.state = InvestigationState.RECEIVED;
        inv.resolution = Resolution.UNKNOWN;
        inv.eventCount = 1;
        inv.createdAt = now;
        inv.updatedAt = now;
        return inv;
    }

    /** The only way to change state: the state machine decides if it's allowed. */
    public void transitionTo(InvestigationState target, String reason, Instant now) {
        InvestigationStateMachine.check(this.state, target);
        this.state = target;
        this.stateReason = reason;
        this.updatedAt = now;
        this.completedAt = target.isOpen() ? null : now;
    }

    /** A duplicate event was linked to this investigation (decision D1). */
    public void linkEvent(Instant now) {
        this.eventCount++;
        this.updatedAt = now;
    }

    public void markRecurrenceOf(Long previousInvestigationId) { this.recurrenceOfId = previousInvestigationId; }

    public void assignScenario(String scenarioId) { this.scenarioId = scenarioId; }

    public void recordToolCall() { this.toolCallsUsed++; }

    public void resetBudget() { this.toolCallsUsed = 0; }

    public void recordAnalysis(String summary, List<String> missing, Instant now) {
        this.summary = summary;
        this.missingInformation = missing == null ? null : String.join("\n", missing);
        this.updatedAt = now;
    }

    /** Only system code calls this; the LLM can never set RESOLVED (rule R-V6). */
    public void updateResolution(Resolution resolution, Instant now) {
        this.resolution = resolution;
        this.updatedAt = now;
    }

    public void markFirstRecommendation(Instant now) {
        if (this.firstRecommendationAt == null) this.firstRecommendationAt = now;
    }

    public String displayId() { return "INV-" + id; }
    public String getDisplayId() { return displayId(); }

    public List<String> getMissingInformationList() {
        return missingInformation == null || missingInformation.isBlank() ? List.of()
                : Arrays.asList(missingInformation.split("\n"));
    }

    public Long getId() { return id; }
    public String getApplicationId() { return applicationId; }
    public String getEventType() { return eventType; }
    public String getResource() { return resource; }
    public String getCorrelationKey() { return correlationKey; }
    public InvestigationState getState() { return state; }
    public Resolution getResolution() { return resolution; }
    public String getStateReason() { return stateReason; }
    public String getSummary() { return summary; }
    public int getEventCount() { return eventCount; }
    public Long getRecurrenceOfId() { return recurrenceOfId; }
    public int getToolCallsUsed() { return toolCallsUsed; }
    public String getScenarioId() { return scenarioId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getFirstRecommendationAt() { return firstRecommendationAt; }
    public Instant getCompletedAt() { return completedAt; }
}
