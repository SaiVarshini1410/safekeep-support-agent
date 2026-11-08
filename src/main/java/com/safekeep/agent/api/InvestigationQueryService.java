package com.safekeep.agent.api;

import com.safekeep.agent.approval.ActionProposal;
import com.safekeep.agent.approval.ProposalRepository;
import com.safekeep.agent.audit.AuditEntry;
import com.safekeep.agent.audit.AuditRepository;
import com.safekeep.agent.investigation.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Read side: list/history and the detail view model (FR-USR-01, FR-CFG-02). */
@Service
public class InvestigationQueryService {

    public record Detail(Investigation investigation, List<Finding> findings, List<ToolCall> evidence,
                         List<Recommendation> recommendations, List<ActionProposal> proposals,
                         List<Message> messages, List<EventRecord> events, List<AuditEntry> audit,
                         List<Feedback> feedback) { }

    private final InvestigationService investigations;
    private final InvestigationRepository repo;
    private final FindingRepository findings;
    private final ToolCallRepository toolCalls;
    private final RecommendationRepository recommendations;
    private final ProposalRepository proposals;
    private final MessageRepository messages;
    private final EventRecordRepository events;
    private final AuditRepository audit;
    private final FeedbackRepository feedback;

    public InvestigationQueryService(InvestigationService investigations, InvestigationRepository repo,
                                     FindingRepository findings, ToolCallRepository toolCalls,
                                     RecommendationRepository recommendations, ProposalRepository proposals,
                                     MessageRepository messages, EventRecordRepository events,
                                     AuditRepository audit, FeedbackRepository feedback) {
        this.investigations = investigations;
        this.repo = repo;
        this.findings = findings;
        this.toolCalls = toolCalls;
        this.recommendations = recommendations;
        this.proposals = proposals;
        this.messages = messages;
        this.events = events;
        this.audit = audit;
        this.feedback = feedback;
    }

    public List<Investigation> list(InvestigationState state, String app, Instant from, Instant to) {
        return repo.findAllByOrderByCreatedAtDesc().stream()
                .filter(i -> state == null || i.getState() == state)
                .filter(i -> app == null || app.isBlank() || i.getApplicationId().equals(app))
                .filter(i -> from == null || !i.getCreatedAt().isBefore(from))
                .filter(i -> to == null || i.getCreatedAt().isBefore(to))
                .toList();
    }

    public Detail detail(Long id) {
        Investigation inv = investigations.get(id);
        return new Detail(inv,
                findings.findByInvestigationIdOrderByIdAsc(id),
                toolCalls.findByInvestigationIdOrderByIdAsc(id),
                recommendations.findByInvestigationIdOrderByPriorityAscIdAsc(id),
                proposals.findByInvestigationIdOrderByIdDesc(id),
                messages.findByInvestigationIdOrderByIdAsc(id),
                events.findByInvestigationIdOrderByReceivedAtAsc(id),
                audit.findByInvestigationIdOrderByIdAsc(id),
                feedback.findByInvestigationId(id));
    }
}
