package com.safekeep.agent.investigation;

import jakarta.persistence.*;

import java.util.Arrays;
import java.util.List;

@Entity
public class Finding {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long investigationId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private FindingKind kind;
    @Column(length = 2000, nullable = false) private String statement;
    @Column(length = 500) private String evidenceIds;
    private boolean downgraded;

    protected Finding() { }

    public Finding(Long investigationId, FindingKind kind, String statement, List<String> evidenceIds, boolean downgraded) {
        this.investigationId = investigationId;
        this.kind = kind;
        this.statement = statement;
        this.evidenceIds = String.join(",", evidenceIds);
        this.downgraded = downgraded;
    }

    public Long getId() { return id; }
    public Long getInvestigationId() { return investigationId; }
    public FindingKind getKind() { return kind; }
    public String getStatement() { return statement; }
    public List<String> getEvidenceIdList() {
        return evidenceIds == null || evidenceIds.isBlank() ? List.of() : Arrays.asList(evidenceIds.split(","));
    }
    public boolean isDowngraded() { return downgraded; }
}
