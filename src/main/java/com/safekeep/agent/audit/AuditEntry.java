package com.safekeep.agent.audit;

import jakarta.persistence.*;

import java.time.Instant;

/** Append-only audit record (FR-ACT-07). investigationId is null for configuration changes. */
@Entity
public class AuditEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long investigationId;
    @Column(nullable = false) private String type;
    @Column(nullable = false) private String actor;
    @Column(length = 2000) private String detail;
    @Column(nullable = false) private Instant createdAt;

    protected AuditEntry() { }

    public AuditEntry(Long investigationId, String type, String actor, String detail, Instant createdAt) {
        this.investigationId = investigationId;
        this.type = type;
        this.actor = actor;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getInvestigationId() { return investigationId; }
    public String getType() { return type; }
    public String getActor() { return actor; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
