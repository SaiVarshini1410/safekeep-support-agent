package com.safekeep.agent.investigation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventRecordRepository extends JpaRepository<EventRecord, Long> {
    Optional<EventRecord> findByEventId(String eventId);
    List<EventRecord> findByInvestigationIdOrderByReceivedAtAsc(Long investigationId);
}
