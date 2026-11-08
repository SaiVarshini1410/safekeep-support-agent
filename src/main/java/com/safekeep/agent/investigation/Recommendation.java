package com.safekeep.agent.investigation;

import jakarta.persistence.*;

@Entity
public class Recommendation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long investigationId;
    @Column(length = 2000, nullable = false) private String body;
    private int priority;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private RecommendationKind kind;

    protected Recommendation() { }

    public Recommendation(Long investigationId, String body, int priority, RecommendationKind kind) {
        this.investigationId = investigationId;
        this.body = body;
        this.priority = priority;
        this.kind = kind;
    }

    public Long getId() { return id; }
    public Long getInvestigationId() { return investigationId; }
    public String getBody() { return body; }
    public int getPriority() { return priority; }
    public RecommendationKind getKind() { return kind; }
}
