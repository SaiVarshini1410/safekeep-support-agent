package com.safekeep.agent.api;

import com.safekeep.agent.audit.AuditService;
import com.safekeep.agent.investigation.Feedback;
import com.safekeep.agent.investigation.FeedbackRepository;
import com.safekeep.agent.investigation.InvestigationService;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** One feedback per user per investigation; the latest wins (FR-USR-06). */
@Service
public class FeedbackService {

    private final FeedbackRepository repo;
    private final InvestigationService investigations;
    private final AuditService audit;
    private final Clock clock;

    public FeedbackService(FeedbackRepository repo, InvestigationService investigations, AuditService audit, Clock clock) {
        this.repo = repo;
        this.investigations = investigations;
        this.audit = audit;
        this.clock = clock;
    }

    public Feedback give(Long id, String user, boolean useful, String remark) {
        investigations.get(id);
        Feedback f = repo.findByInvestigationIdAndUsername(id, user).orElseGet(() -> new Feedback(id, user));
        f.update(useful, remark, clock.instant());
        audit.record(id, "FEEDBACK", user, useful ? "useful" : "not useful");
        return repo.save(f);
    }
}
