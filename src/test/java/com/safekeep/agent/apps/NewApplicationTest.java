package com.safekeep.agent.apps;

import com.safekeep.agent.approval.ActionProposal;
import com.safekeep.agent.approval.ProposalStatus;
import com.safekeep.agent.investigation.InvestigationState;
import com.safekeep.agent.investigation.Resolution;
import com.safekeep.agent.security.Role;
import com.safekeep.agent.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.safekeep.agent.support.FakeLlmClient.*;
import static org.assertj.core.api.Assertions.assertThat;

/** TC-CFG-04: test-app exists only in src/test (YAML + adapter bean). No production class knows about it. */
class NewApplicationTest extends IntegrationTestBase {

    @Test
    @DisplayName("TC-CFG-04 A new application works end to end without changing the shared Agent")
    void newApplicationEndToEnd() {
        Long id = newInvestigation("test-app", "TEST_ALERT", "thing-1", "T-APP");
        llm.script(tool("get_status", Map.of()), text("done"), answer("""
                {"summary":"Thing is broken.","findings":[{"kind":"CONFIRMED","statement":"status BROKEN","evidenceIds":["E1"]}],
                 "missingInformation":[],"recommendations":[],
                 "proposedAction":{"name":"fix_it","params":{},"reason":"fix"},"questionForUser":null,"resolution":"UNRESOLVED"}"""));
        orchestrator.run(id);
        assertThat(inv(id).getState()).isEqualTo(InvestigationState.COMPLETED);

        ActionProposal p = proposalRepo.findByInvestigationIdAndStatus(id, ProposalStatus.PROPOSED).get(0);
        ActionProposal done = approvals.approve(id, p.getId(), "sam", Role.SUPPORT_ENGINEER);
        assertThat(done.getStatus()).isEqualTo(ProposalStatus.SUCCEEDED);
        assertThat(inv(id).getResolution()).isEqualTo(Resolution.RESOLVED);
    }
}
