package com.safekeep.agent.approval;

import com.safekeep.agent.apps.config.ActionConfig;
import com.safekeep.agent.common.NotFoundException;
import com.safekeep.agent.health.MetricsService;
import com.safekeep.agent.investigation.*;
import com.safekeep.agent.security.Role;
import com.safekeep.agent.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalTest extends IntegrationTestBase {

    @Autowired MetricsService metrics;

    private ActionProposal propose(Long id, String action, Map<String, Object> params) {
        Investigation i = inv(id);
        ActionConfig a = registry.find(i.getApplicationId()).orElseThrow().action(action).orElseThrow();
        return approvals.propose(i, a, params, "test reason");
    }

    private long actionExecutions(Long id) {
        return toolCalls(id).stream().filter(ToolCall::isAction).count();
    }

    @Test
    @DisplayName("TC-APR-01 Support engineer approves: executed, verified, RESOLVED")
    void approveSucceeds() {
        Long id = newInvestigation("S-1A");
        ActionProposal p = propose(id, "purge_stale_sessions", Map.of());
        ActionProposal done = approvals.approve(id, p.getId(), "sam", Role.SUPPORT_ENGINEER);

        assertThat(done.getStatus()).isEqualTo(ProposalStatus.SUCCEEDED);
        assertThat(done.getDecidedBy()).isEqualTo("sam");
        assertThat(inv(id).getResolution()).isEqualTo(Resolution.RESOLVED);
        assertThat(toolCalls(id)).extracting(ToolCall::getToolName).containsExactly("purge_stale_sessions", "get_metrics");
        assertThat(messageRepo.findByInvestigationIdOrderByIdAsc(id))
                .anySatisfy(m -> assertThat(m.getBody()).contains("SUCCEEDED"));
    }

    @ParameterizedTest(name = "{0} approves {3} -> allowed={4}")
    @DisplayName("TC-APR-02 Role rules: requiredRole per action")
    @CsvSource({
            "APP_USER,         alice, S-1A, purge_stale_sessions,   false",
            "SUPPORT_ENGINEER, sam,   S-1A, purge_stale_sessions,   true",
            "SUPPORT_ENGINEER, sam,   S-1C, run_expiration_cleanup, false",
            "ADMIN,            adam,  S-1C, run_expiration_cleanup, true",
            "APP_USER,         alice, S-2D, retry_report_job,       true"
    })
    void roleRules(Role role, String user, String scenario, String action, boolean allowed) {
        Long id = newInvestigation(scenario);
        ActionProposal p = propose(id, action, Map.of());
        if (allowed) {
            ActionProposal done = approvals.approve(id, p.getId(), user, role);
            assertThat(done.getStatus()).isIn(ProposalStatus.SUCCEEDED, ProposalStatus.FAILED);
            assertThat(actionExecutions(id)).isEqualTo(1);
        } else {
            assertThatThrownBy(() -> approvals.approve(id, p.getId(), user, role))
                    .isInstanceOf(AccessDeniedException.class);
            assertThat(proposalRepo.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(ProposalStatus.PROPOSED);
            assertThat(actionExecutions(id)).isZero();
            assertThat(auditRepo.findByInvestigationIdOrderByIdAsc(id))
                    .anySatisfy(a -> assertThat(a.getType()).isEqualTo("APPROVAL_DENIED"));
        }
    }

    @Test
    @DisplayName("TC-APR-03 Approval is single use")
    void singleUse() {
        Long id = newInvestigation("S-1A");
        ActionProposal p = propose(id, "purge_stale_sessions", Map.of());
        approvals.approve(id, p.getId(), "sam", Role.SUPPORT_ENGINEER);
        assertThatThrownBy(() -> approvals.approve(id, p.getId(), "sam", Role.SUPPORT_ENGINEER))
                .isInstanceOf(IllegalStateException.class);
        assertThat(actionExecutions(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-APR-04 An expired proposal cannot be approved")
    void expired() {
        Long id = newInvestigation("S-1A");
        ActionProposal p = propose(id, "purge_stale_sessions", Map.of());
        clock.advance(Duration.ofMinutes(16));
        assertThatThrownBy(() -> approvals.approve(id, p.getId(), "sam", Role.SUPPORT_ENGINEER))
                .isInstanceOf(IllegalStateException.class);
        assertThat(proposalRepo.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(ProposalStatus.EXPIRED);
        assertThat(actionExecutions(id)).isZero();
    }

    @Test
    @DisplayName("TC-APR-05 Parameters changed after the proposal are not executed")
    void tamperedParams() {
        Long id = newInvestigation("S-2A");
        ActionProposal p = propose(id, "retry_failed_vms", Map.of("jobId", "Daily-Gold-VMware"));
        p.overrideParamsForTest("{\"jobId\":\"Something-Else\"}");
        proposalRepo.save(p);
        ActionProposal done = approvals.approve(id, p.getId(), "sam", Role.SUPPORT_ENGINEER);
        assertThat(done.getStatus()).isEqualTo(ProposalStatus.FAILED);
        assertThat(done.getOutcome()).contains("Integrity");
        assertThat(actionExecutions(id)).isZero();
        assertThat(auditRepo.findByInvestigationIdOrderByIdAsc(id))
                .anySatisfy(a -> assertThat(a.getType()).isEqualTo("INTEGRITY_FAILED"));
    }

    @Test
    @DisplayName("TC-APR-06 The adapter receives exactly the approved parameters")
    void exactParams() {
        Long id = newInvestigation("S-2A");
        ActionProposal p = propose(id, "retry_failed_vms", Map.of("jobId", "Daily-Gold-VMware"));
        approvals.approve(id, p.getId(), "sam", Role.SUPPORT_ENGINEER);
        ToolCall exec = toolCalls(id).stream().filter(ToolCall::isAction).findFirst().orElseThrow();
        assertThat(exec.getArgsJson()).isEqualTo("{\"jobId\":\"Daily-Gold-VMware\"}");
        assertThat(exec.getProposalId()).isEqualTo(p.getId());
    }

    @Test
    @DisplayName("TC-APR-07 Retry that does not fix the problem is reported as FAILED")
    void failedVerificationReportedHonestly() {
        Long id = newInvestigation("S-2A");
        ActionProposal p = propose(id, "retry_failed_vms", Map.of("jobId", "Daily-Gold-VMware"));
        ActionProposal done = approvals.approve(id, p.getId(), "sam", Role.SUPPORT_ENGINEER);
        assertThat(done.getStatus()).isEqualTo(ProposalStatus.FAILED);
        assertThat(done.getOutcome()).contains("persists");
        assertThat(inv(id).getResolution()).isEqualTo(Resolution.UNRESOLVED);
        assertThat(messageRepo.findByInvestigationIdOrderByIdAsc(id))
                .anySatisfy(m -> assertThat(m.getBody()).contains("FAILED"));
    }

    @Test
    @DisplayName("TC-APR-08 Action that throws is FAILED, never SUCCEEDED")
    void actionThrows() {
        Long id = newInvestigation("backup-server", "HIGH_MEMORY", "backup-server-01", "T-ERR");
        ActionProposal p = propose(id, "purge_stale_sessions", Map.of());
        ActionProposal done = approvals.approve(id, p.getId(), "sam", Role.SUPPORT_ENGINEER);
        assertThat(done.getStatus()).isEqualTo(ProposalStatus.FAILED);
        assertThat(done.getOutcome()).contains("ADAPTER_ERROR");
        assertThat(inv(id).getResolution()).isEqualTo(Resolution.UNRESOLVED);
    }

    @Test
    @DisplayName("TC-APR-09 Improved but still above threshold: PARTIALLY_RESOLVED")
    void partiallyResolved() {
        Long id = newInvestigation("S-1C");
        ActionProposal p = propose(id, "run_expiration_cleanup", Map.of());
        ActionProposal done = approvals.approve(id, p.getId(), "adam", Role.ADMIN);
        assertThat(done.getStatus()).isEqualTo(ProposalStatus.SUCCEEDED);
        assertThat(inv(id).getResolution()).isEqualTo(Resolution.PARTIALLY_RESOLVED);
    }

    @Test
    @DisplayName("TC-APR-10 Rejection is recorded and nothing runs")
    void reject() {
        Long id = newInvestigation("S-1A");
        ActionProposal p = propose(id, "purge_stale_sessions", Map.of());
        ActionProposal r = approvals.reject(id, p.getId(), "sam", Role.SUPPORT_ENGINEER, "change window closed");
        assertThat(r.getStatus()).isEqualTo(ProposalStatus.REJECTED);
        assertThat(r.getDecidedBy()).isEqualTo("sam");
        assertThat(r.getDecisionReason()).isEqualTo("change window closed");
        assertThat(actionExecutions(id)).isZero();
        assertThat(auditRepo.findByInvestigationIdOrderByIdAsc(id))
                .anySatisfy(a -> assertThat(a.getType()).isEqualTo("ACTION_REJECTED"));
    }

    @Test
    @DisplayName("TC-APR-11 Action disabled after proposal: not executed, manual steps added")
    void disabledAfterProposal() {
        Long id = newInvestigation("S-1A");
        ActionProposal p = propose(id, "purge_stale_sessions", Map.of());
        registry.setEnabled("backup-server", "purge_stale_sessions", false);
        ActionProposal done = approvals.approve(id, p.getId(), "sam", Role.SUPPORT_ENGINEER);
        assertThat(done.getStatus()).isEqualTo(ProposalStatus.FAILED);
        assertThat(done.getOutcome()).contains("disabled");
        assertThat(actionExecutions(id)).isZero();
        assertThat(recommendationRepo.findByInvestigationIdOrderByPriorityAscIdAsc(id))
                .anySatisfy(r -> assertThat(r.getKind()).isEqualTo(RecommendationKind.MANUAL));
    }

    @Test
    @DisplayName("TC-APR-12 A proposal cannot be approved through another investigation")
    void wrongInvestigation() {
        Long id = newInvestigation("S-1A");
        Long other = newInvestigation("S-3A");
        ActionProposal p = propose(id, "purge_stale_sessions", Map.of());
        assertThatThrownBy(() -> approvals.approve(other, p.getId(), "sam", Role.SUPPORT_ENGINEER))
                .isInstanceOf(NotFoundException.class);
        assertThat(actionExecutions(id)).isZero();
    }

    @Test
    @DisplayName("TC-MET-03 Success rate and zero actions without approval")
    void actionMetrics() {
        Long ok = newInvestigation("S-1A");
        approvals.approve(ok, propose(ok, "purge_stale_sessions", Map.of()).getId(), "sam", Role.SUPPORT_ENGINEER);
        Long bad = newInvestigation("S-2A");
        approvals.approve(bad, propose(bad, "retry_failed_vms", Map.of()).getId(), "sam", Role.SUPPORT_ENGINEER);
        Map<String, Object> m = metrics.metrics();
        assertThat(m.get("actionSuccessRate")).isEqualTo(50.0);
        assertThat(m.get("actionsWithoutApproval")).isEqualTo(0L);
    }
}
