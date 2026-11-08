package com.safekeep.agent.investigation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
    Optional<Feedback> findByInvestigationIdAndUsername(Long investigationId, String username);
    List<Feedback> findByInvestigationId(Long investigationId);
}
