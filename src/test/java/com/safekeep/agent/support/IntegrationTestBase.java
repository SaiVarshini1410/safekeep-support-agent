package com.safekeep.agent.support;

import com.safekeep.agent.agent.AgentOrchestrator;
import com.safekeep.agent.agent.FollowUpService;
import com.safekeep.agent.apps.AdapterRegistry;
import com.safekeep.agent.apps.ApplicationRegistry;
import com.safekeep.agent.apps.RegisteredApplication;
import com.safekeep.agent.approval.ApprovalService;
import com.safekeep.agent.approval.ProposalRepository;
import com.safekeep.agent.audit.AuditRepository;
import com.safekeep.agent.intake.EventIntakeService;
import com.safekeep.agent.intake.EventRequest;
import com.safekeep.agent.investigation.*;
import com.safekeep.agent.simulator.Scenario;
import com.safekeep.agent.simulator.ScenarioCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared Spring context for service-level tests: real database (in-memory H2), real seed data,
 * scripted LLM, fixed clock, and a mocked scheduler so runs are started explicitly by the test.
 */
@SpringBootTest
@Import(TestBeans.class)
public abstract class IntegrationTestBase {

    protected static final Map<String, String> KEYS =
            Map.of("backup-server", "bk-test-key", "storage-server", "st-test-key", "test-app", "ta-test-key");

    @MockitoBean protected RunScheduler scheduler;

    @Autowired protected FakeLlmClient llm;
    @Autowired protected MutableClock clock;
    @Autowired protected EventIntakeService intake;
    @Autowired protected InvestigationService investigations;
    @Autowired protected AgentOrchestrator orchestrator;
    @Autowired protected FollowUpService followUps;
    @Autowired protected ApprovalService approvals;
    @Autowired protected ApplicationRegistry registry;
    @Autowired protected AdapterRegistry adapters;
    @Autowired protected ScenarioCatalog catalog;
    @Autowired protected InvestigationRepository investigationRepo;
    @Autowired protected EventRecordRepository eventRepo;
    @Autowired protected ToolCallRepository toolCallRepo;
    @Autowired protected FindingRepository findingRepo;
    @Autowired protected RecommendationRepository recommendationRepo;
    @Autowired protected MessageRepository messageRepo;
    @Autowired protected FeedbackRepository feedbackRepo;
    @Autowired protected ProposalRepository proposalRepo;
    @Autowired protected AuditRepository auditRepo;

    @BeforeEach
    void cleanState() {
        llm.reset();
        clock.reset();
        feedbackRepo.deleteAll();
        messageRepo.deleteAll();
        recommendationRepo.deleteAll();
        findingRepo.deleteAll();
        toolCallRepo.deleteAll();
        proposalRepo.deleteAll();
        eventRepo.deleteAll();
        auditRepo.deleteAll();
        investigationRepo.deleteAll();
        for (RegisteredApplication a : registry.all()) {
            a.config().tools().forEach(t -> registry.setEnabled(a.id(), t.name(), true));
            a.config().actions().forEach(x -> registry.setEnabled(a.id(), x.name(), true));
        }
        for (Scenario s : catalog.all()) {
            adapters.get(s.application()).ifPresent(ad -> ad.reset(s.id()));
        }
        adapters.get("backup-server").ifPresent(ad -> ad.reset("T-ERR"));
        adapters.get("test-app").ifPresent(ad -> ad.reset("T-APP"));
    }

    protected EventRequest event(String app, String type, String resource) {
        return new EventRequest("evt-" + UUID.randomUUID(), app, type, resource,
                clock.instant().toString(), Map.of("value", 1));
    }

    /** Creates an investigation for a catalog scenario through the real intake path. */
    protected Long newInvestigation(String scenarioId) {
        Scenario s = catalog.get(scenarioId);
        return intake.receive(KEYS.get(s.application()), event(s.application(), s.eventType(), s.resource()), scenarioId)
                .investigationId();
    }

    protected Long newInvestigation(String app, String type, String resource, String scenarioId) {
        return intake.receive(KEYS.get(app), event(app, type, resource), scenarioId).investigationId();
    }

    protected List<ToolCall> toolCalls(Long id) {
        return toolCallRepo.findByInvestigationIdOrderByIdAsc(id);
    }

    protected Investigation inv(Long id) {
        return investigations.get(id);
    }
}
