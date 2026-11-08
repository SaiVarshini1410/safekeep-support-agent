package com.safekeep.agent.approval;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ProposalRepository extends JpaRepository<ActionProposal, Long> {
    List<ActionProposal> findByInvestigationIdOrderByIdDesc(Long investigationId);
    List<ActionProposal> findByInvestigationIdAndStatus(Long investigationId, ProposalStatus status);
    List<ActionProposal> findByStatusIn(Collection<ProposalStatus> statuses);
}
