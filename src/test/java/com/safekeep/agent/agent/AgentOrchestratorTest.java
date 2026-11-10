package com.safekeep.agent.agent;

import com.safekeep.agent.approval.ActionProposal;
import com.safekeep.agent.approval.ProposalStatus;
import com.safekeep.agent.apps.config.ActionConfig;
import com.safekeep.agent.investigation.*;
import com.safekeep.agent.llm.LlmMessage;
import com.safekeep.agent.llm.LlmRequest;
import com.safekeep.agent.llm.LlmToolSpec;
import com.safekeep.agent.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.safekeep.agent.support.FakeLlmClient.*;
import static org.assertj.core.api.Assertions.assertThat;

class AgentOrchestratorTest extends IntegrationTestBase {

    private static final String GOOD_FINAL = """
            {"summary":"Stale sessions hold memory.",
             "findings":[{"kind":"CONFIRMED","statement":"1172 stale sessions","evidenceIds":["E3"]},
                         {"kind":"SUSPECTED","statement":"Cleanup is stuck since the upgrade","evidenceIds":["E2"]}],
             "missingInformation":[],
             "recommendations":[{"text":"Escalate the cleanup problem","priority":1}],
             "proposedAction":{"name":"purge_stale_sessions","params":{},"reason":"free memory"},
             "questionForUser":null,"resolution":"UNRESOLVED"}""";

    private List<String> toolNames(LlmRequest r) {
        return r.tools().stream().map(LlmToolSpec::name).toList();
    }

    @Test
    @DisplayName("TC-AGT-01 Tool calls become evidence and a validated answer completes the run")
    void happyPath() {
        Long id = newInvestigation("S-1A");
        llm.script(tool("get_metrics", Map.of("metric", "memory")), tool("get_top_processes", Map.of()),
                tool("get_job_sessions", Map.of()), text("done"), answer(GOOD_FINAL));
        orchestrator.run(id);

        assertThat(toolCalls(id)).extracting(ToolCall::getEvidenceId).containsExactly("E1", "E2", "E3");
        assertThat(toolCalls(id)).allSatisfy(t -> assertThat(t.getStatus()).isEqualTo(ToolCallStatus.OK));
        Investigation i = inv(id);
        assertThat(i.getState()).isEqualTo(InvestigationState.COMPLETED);
        assertThat(i.getResolution()).isEqualTo(Resolution.UNRESOLVED);
        assertThat(i.getFirstRecommendationAt()).isNotNull();
        assertThat(findingRepo.findByInvestigationIdOrderByIdAsc(id)).hasSize(2);
        assertThat(proposalRepo.findByInvestigationIdAndStatus(id, ProposalStatus.PROPOSED)).hasSize(1);
    }

    @Test
    @DisplayName("TC-AGT-02 The model is offered only this event type's read tools; actions are text only")
    void onlyPermittedToolsOffered() {
        Long id = newInvestigation("S-1A");
        orchestrator.run(id);
        LlmRequest first = llm.requests().get(0);
        assertThat(toolNames(first)).containsExactlyInAnyOrder("get_metrics", "get_top_processes",
                "get_job_sessions", "get_logs", "get_recent_changes", "search_runbook");
        assertThat(toolNames(first)).doesNotContain("purge_stale_sessions", "get_pool_status");
        assertThat(first.messages().get(0).content()).contains("Proposable actions").contains("purge_stale_sessions")
                .doesNotContain("clear_expired_auth_sessions").doesNotContain("retry_failed_vms");   // scoped to HIGH_MEMORY
    }

    @Test
    @DisplayName("TC-AGT-03 A tool disabled by the admin is never offered")
    void disabledToolNotOffered() {
        registry.setEnabled("backup-server", "get_logs", false);
        Long id = newInvestigation("S-1A");
        llm.script(tool("get_metrics", Map.of("metric", "memory")), text("done"));
        orchestrator.run(id);
        assertThat(llm.requests()).filteredOn(r -> !r.tools().isEmpty())
                .allSatisfy(r -> assertThat(toolNames(r)).doesNotContain("get_logs"));
    }

    @Test
    @DisplayName("TC-AGT-04 The run stops after 8 tool calls and reports missing evidence")
    void budgetEnforced() {
        Long id = newInvestigation("S-1A");
        for (int h = 1; h <= 8; h++) llm.script(tool("get_metrics", Map.of("metric", "memory", "hours", h)));
        llm.script(answer("""
                {"summary":"Partial","findings":[{"kind":"SUSPECTED","statement":"memory high","evidenceIds":["E1"]}],
                 "missingInformation":[],"recommendations":[],"proposedAction":null,"questionForUser":null,"resolution":"UNKNOWN"}"""));
        orchestrator.run(id);
        assertThat(toolCalls(id)).hasSize(8);
        assertThat(llm.anyRequestContains("You have used all 8 available tool calls")).isTrue();
        assertThat(inv(id).getMissingInformationList()).anySatisfy(m -> assertThat(m).contains("stopped after 8"));
        assertThat(inv(id).getState()).isEqualTo(InvestigationState.COMPLETED);
    }

    @Test
    @DisplayName("TC-AGT-05 / TC-INV-04 LLM unavailable: 3 attempts, then UNABLE_TO_COMPLETE, evidence kept")
    void llmUnavailable() {
        Long id = newInvestigation("S-1A");
        llm.script(tool("get_metrics", Map.of("metric", "memory")));
        llm.failWhenEmpty(true);
        orchestrator.run(id);
        assertThat(llm.requests()).hasSize(4);   // 1 successful + 3 failed attempts
        Investigation i = inv(id);
        assertThat(i.getState()).isEqualTo(InvestigationState.UNABLE_TO_COMPLETE);
        assertThat(i.getStateReason()).isEqualTo("AI service unavailable");
        assertThat(toolCalls(id)).hasSize(1);
        assertThat(messageRepo.findByInvestigationIdOrderByIdAsc(id))
                .anySatisfy(m -> assertThat(m.getBody()).contains("Evidence collected so far is kept"));
    }

    @Test
    @DisplayName("TC-AGT-06 Malformed answer once: one repair request, then completed")
    void repairOnce() {
        Long id = newInvestigation("S-1A");
        llm.script(text("done"), answer("this is not json"), answer(GOOD_FINAL));
        orchestrator.run(id);
        assertThat(llm.anyRequestContains("Your previous answer was invalid")).isTrue();
        assertThat(inv(id).getState()).isEqualTo(InvestigationState.COMPLETED);
    }

    @Test
    @DisplayName("TC-AGT-07 Malformed answer twice: UNABLE_TO_COMPLETE")
    void repairFails() {
        Long id = newInvestigation("S-1A");
        llm.script(text("done"), answer("nope"), answer("still nope"));
        orchestrator.run(id);
        assertThat(inv(id).getState()).isEqualTo(InvestigationState.UNABLE_TO_COMPLETE);
        assertThat(inv(id).getStateReason()).isEqualTo("Invalid AI output");
    }

    @Test
    @DisplayName("TC-AGT-08 Adapter error is returned to the model and the run continues")
    void adapterErrorReturnedToModel() {
        Long id = newInvestigation("backup-server", "HIGH_MEMORY", "backup-server-01", "T-ERR");
        llm.script(tool("get_logs", Map.of()), text("done"), answer(FakeLlmClientDefaults.FINAL));
        orchestrator.run(id);
        ToolCall t = toolCalls(id).get(0);
        assertThat(t.getStatus()).isEqualTo(ToolCallStatus.ERROR);
        assertThat(t.getErrorCode()).isEqualTo("ADAPTER_ERROR");
        assertThat(llm.requests().get(1).messages()).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo("tool");
            assertThat(m.content()).contains("ADAPTER_ERROR");
        });
        assertThat(inv(id).getState()).isEqualTo(InvestigationState.COMPLETED);
    }

    @Test
    @DisplayName("TC-AGT-09 A question for the user (after using remaining tools) leads to WAITING_FOR_INFO")
    void questionWaits() {
        Long id = newInvestigation("S-2C");
        String withQuestion = """
                {"summary":"Unclear","findings":[],"missingInformation":["VM snapshot state"],"recommendations":[],
                 "proposedAction":null,"questionForUser":"Were the VMs changed recently?","resolution":"UNKNOWN"}""";
        llm.script(text("done"), answer(withQuestion), text("still unclear"), answer(withQuestion));
        orchestrator.run(id);
        assertThat(llm.anyRequestContains("Do not ask the user yet")).isTrue();   // guard ran once
        assertThat(inv(id).getState()).isEqualTo(InvestigationState.WAITING_FOR_INFO);
        assertThat(messageRepo.findByInvestigationIdOrderByIdAsc(id))
                .anySatisfy(m -> assertThat(m.getType()).isEqualTo(MessageType.AGENT_QUESTION));
    }

    @Test
    @DisplayName("TC-AGT-10 The user's reply resumes the investigation")
    void replyResumes() {
        Long id = newInvestigation("S-2C");
        investigations.transition(id, InvestigationState.INVESTIGATING, null, "test");
        investigations.transition(id, InvestigationState.WAITING_FOR_INFO, null, "test");
        followUps.reply(id, "sam", "The VMs were migrated to a new host yesterday");
        assertThat(inv(id).getState()).isEqualTo(InvestigationState.INVESTIGATING);
        orchestrator.run(id);
        assertThat(llm.anyRequestContains("migrated to a new host")).isTrue();
        assertThat(inv(id).getState()).isEqualTo(InvestigationState.COMPLETED);
    }

    @Test
    @DisplayName("TC-AGT-11 A follow-up question is answered with one call, no tools, no state change")
    void followUpQuestion() {
        Long id = newInvestigation("S-1A");
        llm.script(tool("get_job_sessions", Map.of()), text("done"), answer(GOOD_FINAL));
        orchestrator.run(id);
        int before = llm.requests().size();
        long toolsBefore = toolCallRepo.count();
        llm.script(text("It will return unless cleanup is fixed."));

        Message answer = followUps.ask(id, "sam", "Will this come back?");

        assertThat(llm.requests()).hasSize(before + 1);
        assertThat(llm.requests().get(before).tools()).isEmpty();
        assertThat(answer.getBody()).contains("unless cleanup is fixed");
        assertThat(inv(id).getState()).isEqualTo(InvestigationState.COMPLETED);
        assertThat(toolCallRepo.count()).isEqualTo(toolsBefore);
    }

    @Test
    @DisplayName("TC-AGT-12 More investigation reopens, expires proposals, resets budget and uses context")
    void reinvestigate() {
        Long id = newInvestigation("S-1A");
        llm.script(tool("get_job_sessions", Map.of()), text("done"), answer(GOOD_FINAL));
        orchestrator.run(id);
        ActionProposal pending = proposalRepo.findByInvestigationIdAndStatus(id, ProposalStatus.PROPOSED).get(0);

        followUps.reinvestigate(id, "sam", "We restarted the scheduler last night");

        assertThat(inv(id).getState()).isEqualTo(InvestigationState.INVESTIGATING);
        assertThat(inv(id).getToolCallsUsed()).isZero();
        assertThat(proposalRepo.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(ProposalStatus.EXPIRED);
        orchestrator.run(id);
        assertThat(llm.anyRequestContains("We restarted the scheduler last night")).isTrue();
        assertThat(llm.anyRequestContains("Previous findings")).isTrue();
    }

    @Test
    @DisplayName("TC-AGT-14 The model never receives an API key")
    void noSecretsToModel() {
        Long id = newInvestigation("S-1A");
        llm.script(tool("get_metrics", Map.of("metric", "memory")), tool("search_runbook", Map.of("query", "memory")),
                text("done"), answer(GOOD_FINAL));
        orchestrator.run(id);
        for (LlmRequest r : llm.requests()) {
            for (LlmMessage m : r.messages()) {
                assertThat(m.content()).doesNotContain("bk-test-key").doesNotContainIgnoringCase("apiKey");
            }
        }
    }

    @Test
    @DisplayName("TC-AGT-15 Two investigations running concurrently never mix their evidence")
    void concurrentRunsIsolated() throws Exception {
        Long memory = newInvestigation("S-1A");
        Long vcenter = newInvestigation("S-3A");
        llm.scriptFor("on backup-server-01", tool("get_job_sessions", Map.of()), tool("get_metrics", Map.of("metric", "memory")),
                text("done"), answer(GOOD_FINAL));
        llm.scriptFor("on vcenter-prod", tool("get_dependency_status", Map.of()), text("done"), answer(FakeLlmClientDefaults.FINAL));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> a = pool.submit(() -> orchestrator.run(memory));
        Future<?> b = pool.submit(() -> orchestrator.run(vcenter));
        a.get(30, TimeUnit.SECONDS);
        b.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(toolCalls(memory)).extracting(ToolCall::getToolName).containsExactly("get_job_sessions", "get_metrics");
        assertThat(toolCalls(vcenter)).extracting(ToolCall::getToolName).containsExactly("get_dependency_status");
        assertThat(toolCalls(vcenter).get(0).getEvidenceId()).isEqualTo("E1");
        assertThat(findingRepo.findByInvestigationIdOrderByIdAsc(vcenter)).isEmpty();
        assertThat(findingRepo.findByInvestigationIdOrderByIdAsc(memory)).hasSize(2);
    }

    @Test
    @DisplayName("TC-AGT-16 An identical repeated tool call is not executed again")
    void duplicateCallSkipped() {
        Long id = newInvestigation("S-1A");
        llm.script(tool("get_job_sessions", Map.of()), tool("get_job_sessions", Map.of()), text("done"), answer(GOOD_FINAL));
        orchestrator.run(id);
        assertThat(toolCalls(id)).hasSize(1);
        assertThat(llm.anyRequestContains("Already called with the same arguments; see E1")).isTrue();
    }

    /** Small helper so tests read clearly. */
    static final class FakeLlmClientDefaults {
        static final String FINAL = com.safekeep.agent.support.FakeLlmClient.DEFAULT_FINAL;
    }
}
