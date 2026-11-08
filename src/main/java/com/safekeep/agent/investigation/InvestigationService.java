package com.safekeep.agent.investigation;

import com.safekeep.agent.audit.AuditService;
import com.safekeep.agent.common.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * All changes to an Investigation go through mutate(): short, locked, load-modify-save.
 * This prevents lost updates between the agent run and incoming duplicate events.
 */
@Service
public class InvestigationService {

    private final InvestigationRepository repo;
    private final AuditService audit;
    private final ProgressPublisher progress;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public InvestigationService(InvestigationRepository repo, AuditService audit, ProgressPublisher progress,
                                TransactionTemplate tx, Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.progress = progress;
        this.tx = tx;
        this.clock = clock;
    }

    public Investigation get(Long id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Investigation " + id + " not found"));
    }

    public Investigation mutate(Long id, Consumer<Investigation> change) {
        synchronized (locks.computeIfAbsent(id, k -> new Object())) {
            return tx.execute(status -> {
                Investigation inv = get(id);
                change.accept(inv);
                return repo.save(inv);
            });
        }
    }

    /** State change with audit and progress event (FR-INV-02, FR-INV-03). */
    public Investigation transition(Long id, InvestigationState target, String reason, String actor) {
        InvestigationState[] from = new InvestigationState[1];
        try {
            Investigation inv = mutate(id, i -> {
                from[0] = i.getState();
                i.transitionTo(target, reason, clock.instant());
            });
            audit.record(id, "STATE_CHANGED", actor, from[0] + " -> " + target + (reason == null ? "" : " (" + reason + ")"));
            progress.publish(new ProgressEvent(id, "state", from[0] + " -> " + target
                    + (reason == null ? "" : ": " + reason), clock.instant()));
            return inv;
        } catch (IllegalStateTransitionException e) {
            audit.record(id, "INVALID_TRANSITION", actor, e.getMessage());
            throw e;
        }
    }

    public void publish(Long id, String type, String message) {
        progress.publish(new ProgressEvent(id, type, message, clock.instant()));
    }
}
