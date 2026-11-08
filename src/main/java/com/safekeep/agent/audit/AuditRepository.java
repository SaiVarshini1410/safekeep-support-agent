package com.safekeep.agent.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditRepository extends JpaRepository<AuditEntry, Long> {
    List<AuditEntry> findByInvestigationIdOrderByIdAsc(Long investigationId);
    List<AuditEntry> findByType(String type);
}
