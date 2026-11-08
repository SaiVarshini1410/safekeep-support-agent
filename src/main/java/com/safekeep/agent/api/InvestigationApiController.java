package com.safekeep.agent.api;

import com.safekeep.agent.agent.FollowUpService;
import com.safekeep.agent.approval.ActionProposal;
import com.safekeep.agent.approval.ApprovalService;
import com.safekeep.agent.investigation.Feedback;
import com.safekeep.agent.investigation.Investigation;
import com.safekeep.agent.investigation.InvestigationState;
import com.safekeep.agent.investigation.Message;
import com.safekeep.agent.investigation.ProgressPublisher;
import com.safekeep.agent.security.CurrentUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/investigations")
public class InvestigationApiController {

    public record MessageRequest(String type, String text) { }
    public record DecisionRequest(String reason) { }
    public record FeedbackRequest(boolean useful, String comment) { }

    private final InvestigationQueryService query;
    private final FollowUpService followUps;
    private final ApprovalService approvals;
    private final FeedbackService feedback;
    private final ProgressPublisher progress;

    public InvestigationApiController(InvestigationQueryService query, FollowUpService followUps,
                                      ApprovalService approvals, FeedbackService feedback, ProgressPublisher progress) {
        this.query = query;
        this.followUps = followUps;
        this.approvals = approvals;
        this.feedback = feedback;
        this.progress = progress;
    }

    @GetMapping
    public List<Investigation> list(@RequestParam(required = false) InvestigationState state,
                                    @RequestParam(required = false) String app,
                                    @RequestParam(required = false) Instant from,
                                    @RequestParam(required = false) Instant to) {
        return query.list(state, app, from, to);
    }

    @GetMapping("/{id}")
    public InvestigationQueryService.Detail detail(@PathVariable Long id) {
        return query.detail(id);
    }

    @GetMapping(value = "/{id}/stream", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable Long id) {
        query.detail(id);
        return progress.subscribe(id);
    }

    @PostMapping("/{id}/messages")
    public Map<String, Object> message(@PathVariable Long id, @RequestBody MessageRequest req) {
        String user = CurrentUser.username();
        String type = req.type() == null ? "QUESTION" : req.type().toUpperCase();
        switch (type) {
            case "QUESTION" -> {
                Message answer = followUps.ask(id, user, req.text());
                return Map.of("status", "ANSWERED", "answer", answer.getBody());
            }
            case "REPLY" -> followUps.reply(id, user, req.text());
            case "CONTEXT" -> followUps.reinvestigate(id, user, req.text());
            default -> throw new IllegalArgumentException("type must be QUESTION, REPLY or CONTEXT");
        }
        return Map.of("status", "ACCEPTED");
    }

    @PostMapping("/{id}/reinvestigate")
    public Map<String, Object> reinvestigate(@PathVariable Long id) {
        followUps.reinvestigate(id, CurrentUser.username(), null);
        return Map.of("status", "ACCEPTED");
    }

    @PostMapping("/{id}/proposals/{pid}/approve")
    public ActionProposal approve(@PathVariable Long id, @PathVariable Long pid) {
        return approvals.approve(id, pid, CurrentUser.username(), CurrentUser.role());
    }

    @PostMapping("/{id}/proposals/{pid}/reject")
    public ActionProposal reject(@PathVariable Long id, @PathVariable Long pid,
                                 @RequestBody(required = false) DecisionRequest req) {
        return approvals.reject(id, pid, CurrentUser.username(), CurrentUser.role(), req == null ? null : req.reason());
    }

    @PostMapping("/{id}/feedback")
    public Feedback feedback(@PathVariable Long id, @RequestBody FeedbackRequest req) {
        return feedback.give(id, CurrentUser.username(), req.useful(), req.comment());
    }
}
