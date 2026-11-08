package com.safekeep.agent.investigation;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/** After a restart: interrupted runs fail visibly; queued runs are resubmitted (Spec Section 13.1). */
@Component
public class StartupRecovery {

    private final InvestigationRepository repo;
    private final InvestigationService service;
    private final RunScheduler scheduler;

    public StartupRecovery(InvestigationRepository repo, InvestigationService service, RunScheduler scheduler) {
        this.repo = repo;
        this.service = service;
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for (Investigation inv : repo.findByStateIn(List.of(InvestigationState.INVESTIGATING))) {
            service.transition(inv.getId(), InvestigationState.UNABLE_TO_COMPLETE, "Interrupted by restart", "SYSTEM");
        }
        for (Investigation inv : repo.findByStateIn(List.of(InvestigationState.RECEIVED))) {
            scheduler.submit(inv.getId());
        }
    }
}
