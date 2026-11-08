package com.safekeep.agent.investigation;

import jakarta.persistence.*;

import java.time.Instant;

/** One tool request and its outcome. This is the evidence that findings cite (FR-DATA-03). */
@Entity
public class ToolCall {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long investigationId;
    @Column(nullable = false) private String evidenceId;
    @Column(nullable = false) private String toolName;
    @Column(length = 2000) private String argsJson;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ToolCallStatus status;
    private String errorCode;
    @Column(length = 20000) private String resultJson;
    private long durationMs;
    private String actor;
    private boolean action;
    private Long proposalId;
    @Column(nullable = false) private Instant createdAt;

    protected ToolCall() { }

    public ToolCall(Long investigationId, String evidenceId, String toolName, String argsJson,
                    ToolCallStatus status, String errorCode, String resultJson, long durationMs,
                    String actor, boolean action, Long proposalId, Instant createdAt) {
        this.investigationId = investigationId;
        this.evidenceId = evidenceId;
        this.toolName = toolName;
        this.argsJson = argsJson;
        this.status = status;
        this.errorCode = errorCode;
        this.resultJson = resultJson;
        this.durationMs = durationMs;
        this.actor = actor;
        this.action = action;
        this.proposalId = proposalId;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getInvestigationId() { return investigationId; }
    public String getEvidenceId() { return evidenceId; }
    public String getToolName() { return toolName; }
    public String getArgsJson() { return argsJson; }
    public ToolCallStatus getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public String getResultJson() { return resultJson; }
    public long getDurationMs() { return durationMs; }
    public String getActor() { return actor; }
    public boolean isAction() { return action; }
    public Long getProposalId() { return proposalId; }
    public Instant getCreatedAt() { return createdAt; }
}
