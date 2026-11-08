package com.safekeep.agent.agent;

import com.safekeep.agent.approval.ApprovalService;
import com.safekeep.agent.common.Json;
import com.safekeep.agent.investigation.*;
import com.safekeep.agent.llm.*;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/** Questions, replies, extra context and re-investigation (Spec Section 8.7, decision D7). */
@Service
public class FollowUpService {

    private final InvestigationService investigations;
    private final MessageRepository messages;
    private final FindingRepository findings;
    private final ToolCallRepository toolCalls;
    private final ApprovalService approvals;
    private final RunScheduler scheduler;
    private final LlmClient llm;
    private final PromptBuilder prompts;
    private final Json json;
    private final Clock clock;

    public FollowUpService(InvestigationService investigations, MessageRepository messages, FindingRepository findings,
                           ToolCallRepository toolCalls, ApprovalService approvals, RunScheduler scheduler,
                           LlmClient llm, PromptBuilder prompts, Json json, Clock clock) {
        this.investigations = investigations;
        this.messages = messages;
        this.findings = findings;
        this.toolCalls = toolCalls;
        this.approvals = approvals;
        this.scheduler = scheduler;
        this.llm = llm;
        this.prompts = prompts;
        this.json = json;
        this.clock = clock;
    }

    /** A plain question: one LLM call, no tools, no state change (TC-AGT-11). */
    public Message ask(Long id, String user, String question) {
        Investigation inv = investigations.get(id);
        messages.save(new Message(id, MessageAuthor.USER, MessageType.QUESTION, question, user, clock.instant()));
        StringBuilder digest = new StringBuilder();
        for (ToolCall t : toolCalls.findByInvestigationIdOrderByIdAsc(id)) {
            String r = t.getResultJson() == null ? "" : t.getResultJson();
            digest.append('[').append(t.getEvidenceId()).append("] ").append(t.getToolName()).append(": ")
                  .append(r.length() > 400 ? r.substring(0, 400) + "..." : r).append('\n');
        }
        String answer;
        try {
            LlmResponse r = llm.chat(new LlmRequest(List.of(
                    LlmMessage.system(prompts.answerSystem(inv, findings.findByInvestigationIdOrderByIdAsc(id), digest.toString())),
                    LlmMessage.user(question)), List.of(), false));
            answer = r.content().isBlank() ? "No answer was produced." : r.content().trim();
        } catch (LlmUnavailableException e) {
            answer = "The AI service is unavailable right now. Please try again.";
        }
        return messages.save(new Message(id, MessageAuthor.AGENT, MessageType.ANSWER, answer, null, clock.instant()));
    }

    /** Reply to the Agent's question: resume the run (TC-AGT-10). */
    public void reply(Long id, String user, String text) {
        Investigation inv = investigations.get(id);
        if (inv.getState() != InvestigationState.WAITING_FOR_INFO) {
            throw new IllegalStateException("The Agent is not waiting for a reply");
        }
        messages.save(new Message(id, MessageAuthor.USER, MessageType.REPLY, text, user, clock.instant()));
        investigations.transition(id, InvestigationState.INVESTIGATING, "User replied", user);
        scheduler.submit(id);
    }

    /** Extra context or "investigate more": reopen a finished investigation (TC-AGT-12). */
    public void reinvestigate(Long id, String user, String context) {
        Investigation inv = investigations.get(id);
        if (inv.getState() == InvestigationState.WAITING_FOR_INFO && context != null && !context.isBlank()) {
            reply(id, user, context);
            return;
        }
        if (context != null && !context.isBlank()) {
            messages.save(new Message(id, MessageAuthor.USER, MessageType.CONTEXT, context, user, clock.instant()));
        }
        if (inv.getState() == InvestigationState.INVESTIGATING || inv.getState() == InvestigationState.RECEIVED) {
            return;   // already running; the context is used by the next run
        }
        approvals.expirePending(id, "New investigation requested");
        investigations.mutate(id, Investigation::resetBudget);
        investigations.transition(id, InvestigationState.INVESTIGATING,
                context == null || context.isBlank() ? "More investigation requested" : "New context provided", user);
        scheduler.submit(id);
    }
}
