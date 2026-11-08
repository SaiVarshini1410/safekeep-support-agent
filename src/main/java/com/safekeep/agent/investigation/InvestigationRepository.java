package com.safekeep.agent.investigation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InvestigationRepository extends JpaRepository<Investigation, Long> {

    Optional<Investigation> findFirstByCorrelationKeyAndStateInOrderByCreatedAtDesc(
            String correlationKey, Collection<InvestigationState> states);

    List<Investigation> findByStateIn(Collection<InvestigationState> states);

    List<Investigation> findAllByOrderByCreatedAtDesc();
}
