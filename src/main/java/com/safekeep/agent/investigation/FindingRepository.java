package com.safekeep.agent.investigation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface FindingRepository extends JpaRepository<Finding, Long> {
    List<Finding> findByInvestigationIdOrderByIdAsc(Long investigationId);

    @Transactional
    void deleteByInvestigationId(Long investigationId);
}
