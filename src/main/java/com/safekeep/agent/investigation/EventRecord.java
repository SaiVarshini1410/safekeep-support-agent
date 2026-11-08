package com.safekeep.agent.investigation;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
public class EventRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long investigationId;
    @Column(nullable = false, unique = true) private String eventId;
    @Column(nullable = false) private Instant receivedAt;
    @Column(length = 4000) private String payload;

    protected EventRecord() { }

    public EventRecord(Long investigationId, String eventId, Instant receivedAt, String payload) {
        this.investigationId = investigationId;
        this.eventId = eventId;
        this.receivedAt = receivedAt;
        this.payload = payload;
    }

    public Long getId() { return id; }
    public Long getInvestigationId() { return investigationId; }
    public String getEventId() { return eventId; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getPayload() { return payload; }
}
