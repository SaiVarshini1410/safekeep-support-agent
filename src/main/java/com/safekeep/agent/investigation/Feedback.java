package com.safekeep.agent.investigation;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
public class Feedback {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long investigationId;
    @Column(nullable = false) private String username;
    private boolean useful;
    @Column(length = 1000) private String remark;
    @Column(nullable = false) private Instant createdAt;

    protected Feedback() { }

    public Feedback(Long investigationId, String username) {
        this.investigationId = investigationId;
        this.username = username;
    }

    public void update(boolean useful, String remark, Instant now) {
        this.useful = useful;
        this.remark = remark;
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public Long getInvestigationId() { return investigationId; }
    public String getUsername() { return username; }
    public boolean isUseful() { return useful; }
    public String getRemark() { return remark; }
    public Instant getCreatedAt() { return createdAt; }
}
