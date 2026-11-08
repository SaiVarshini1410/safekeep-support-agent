package com.safekeep.agent.investigation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {
    List<Recommendation> findByInvestigationIdOrderByPriorityAscIdAsc(Long investigationId);

    @Transactional
    void deleteByInvestigationId(Long investigationId);
}
