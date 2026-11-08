package com.safekeep.agent.approval;

import com.safekeep.agent.security.Role;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
public class ActionProposal {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long investigationId;
    @Column(nullable = false) private String actionName;
    @Column(length = 2000) private String description;
    @Column(length = 2000) private String paramsJson;
    @Column(nullable = false) private String paramsHash;
    private String risk;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Role requiredRole;
    @Column(length = 2000) private String reason;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ProposalStatus status;
    private String decidedBy;
    private Instant decidedAt;
    @Column(length = 1000) private String decisionReason;
    @Column(length = 2000) private String outcome;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant expiresAt;

    protected ActionProposal() { }

    public ActionProposal(Long investigationId, String actionName, String description, String paramsJson,
                          String paramsHash, String risk, Role requiredRole, String reason,
                          Instant createdAt, Instant expiresAt) {
        this.investigationId = investigationId;
        this.actionName = actionName;
        this.description = description;
        this.paramsJson = paramsJson;
        this.paramsHash = paramsHash;
        this.risk = risk;
        this.requiredRole = requiredRole;
        this.reason = reason;
        this.status = ProposalStatus.PROPOSED;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    void setStatus(ProposalStatus status) { this.status = status; }

    void decide(ProposalStatus status, String user, Instant at, String reason) {
        this.status = status;
        this.decidedBy = user;
        this.decidedAt = at;
        this.decisionReason = reason;
    }

    void finish(ProposalStatus status, String outcome) {
        this.status = status;
        this.outcome = outcome;
    }

    /** Test hook to simulate tampering (TC-APR-05). */
    public void overrideParamsForTest(String json) { this.paramsJson = json; }

    public Long getId() { return id; }
    public Long getInvestigationId() { return investigationId; }
    public String getActionName() { return actionName; }
    public String getDescription() { return description; }
    public String getParamsJson() { return paramsJson; }
    public String getParamsHash() { return paramsHash; }
    public String getRisk() { return risk; }
    public Role getRequiredRole() { return requiredRole; }
    public String getReason() { return reason; }
    public ProposalStatus getStatus() { return status; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionReason() { return decisionReason; }
    public String getOutcome() { return outcome; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
