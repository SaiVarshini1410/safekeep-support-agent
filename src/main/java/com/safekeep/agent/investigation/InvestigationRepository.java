package com.safekeep.agent.investigation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestigationRepository extends JpaRepository<Investigation, Long> {
}