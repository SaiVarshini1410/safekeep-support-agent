package com.safekeep.agent.investigation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ToolCallRepository extends JpaRepository<ToolCall, Long> {
    List<ToolCall> findByInvestigationIdOrderByIdAsc(Long investigationId);
    long countByInvestigationId(Long investigationId);
    long countByActionTrueAndProposalIdIsNull();
}
