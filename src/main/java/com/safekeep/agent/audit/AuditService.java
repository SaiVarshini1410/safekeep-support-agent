package com.safekeep.agent.audit;

import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class AuditService {

    private final AuditRepository repo;
    private final Clock clock;

    public AuditService(AuditRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public void record(Long investigationId, String type, String actor, String detail) {
        String d = detail == null ? null : (detail.length() > 2000 ? detail.substring(0, 1997) + "..." : detail);
        repo.save(new AuditEntry(investigationId, type, actor, d, clock.instant()));
    }
}
