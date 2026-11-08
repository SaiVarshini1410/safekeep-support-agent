package com.safekeep.agent.health;

import com.safekeep.agent.approval.ActionProposal;
import com.safekeep.agent.approval.ProposalRepository;
import com.safekeep.agent.approval.ProposalStatus;
import com.safekeep.agent.investigation.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/** PRD success measures MET-01 to MET-03. */
@Service
public class MetricsService {

    private final InvestigationRepository investigations;
    private final FindingRepository findings;
    private final FeedbackRepository feedback;
    private final ProposalRepository proposals;
    private final ToolCallRepository toolCalls;

    public MetricsService(InvestigationRepository investigations, FindingRepository findings,
                          FeedbackRepository feedback, ProposalRepository proposals, ToolCallRepository toolCalls) {
        this.investigations = investigations;
        this.findings = findings;
        this.feedback = feedback;
        this.proposals = proposals;
        this.toolCalls = toolCalls;
    }

    public Map<String, Object> metrics() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Investigation> all = investigations.findAll();

        // MET-01: median seconds from event to first recommendation
        List<Long> secs = all.stream().filter(i -> i.getFirstRecommendationAt() != null)
                .map(i -> Duration.between(i.getCreatedAt(), i.getFirstRecommendationAt()).toSeconds())
                .sorted().toList();
        out.put("medianSecondsToFirstRecommendation", median(secs));

        // MET-02: evidence-backed findings, positive feedback
        List<Investigation> analysed = all.stream().filter(i -> i.getSummary() != null).toList();
        long withEvidence = analysed.stream().filter(i -> findings.findByInvestigationIdOrderByIdAsc(i.getId())
                .stream().anyMatch(f -> !f.getEvidenceIdList().isEmpty())).count();
        out.put("percentWithEvidence", pct(withEvidence, analysed.size()));
        List<Feedback> fb = feedback.findAll();
        out.put("percentPositiveFeedback", pct(fb.stream().filter(Feedback::isUseful).count(), fb.size()));
        out.put("feedbackCount", fb.size());

        // MET-03: approved action success rate; executions without approval
        List<ActionProposal> done = proposals.findByStatusIn(List.of(ProposalStatus.SUCCEEDED, ProposalStatus.FAILED))
                .stream().filter(p -> p.getDecidedBy() != null).toList();
        out.put("actionSuccessRate", pct(done.stream().filter(p -> p.getStatus() == ProposalStatus.SUCCEEDED).count(), done.size()));
        out.put("actionsExecuted", done.size());
        out.put("actionsWithoutApproval", toolCalls.countByActionTrueAndProposalIdIsNull());
        return out;
    }

    static Double median(List<Long> sorted) {
        if (sorted.isEmpty()) return null;
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    static Double pct(long part, long total) {
        return total == 0 ? null : Math.round(1000.0 * part / total) / 10.0;
    }
}
