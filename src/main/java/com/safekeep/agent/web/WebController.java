package com.safekeep.agent.web;

import com.safekeep.agent.api.AdminApiController;
import com.safekeep.agent.api.InvestigationQueryService;
import com.safekeep.agent.approval.ActionProposal;
import com.safekeep.agent.approval.ProposalStatus;
import com.safekeep.agent.audit.AuditEntry;
import com.safekeep.agent.health.HealthService;
import com.safekeep.agent.health.MetricsService;
import com.safekeep.agent.investigation.*;
import com.safekeep.agent.security.CurrentUser;
import com.safekeep.agent.simulator.Scenario;
import com.safekeep.agent.simulator.ScenarioCatalog;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.*;
import java.util.stream.Collectors;

/** Server-rendered pages (Spec Section 12). All actions go through the REST API. */
@Controller
public class WebController {

    private static final Set<String> TIMELINE_TYPES = Set.of("INVESTIGATION_CREATED", "EVENT_LINKED", "STATE_CHANGED",
            "TOOL_CALL", "ACTION_PROPOSED", "ACTION_APPROVED", "ACTION_EXECUTED", "ACTION_OUTCOME", "ACTION_REJECTED",
            "APPROVAL_DENIED", "PROPOSAL_EXPIRED", "ACTION_BLOCKED", "INTEGRITY_FAILED");

    private final InvestigationQueryService query;
    private final ScenarioCatalog catalog;
    private final AdminApiController admin;
    private final HealthService health;
    private final MetricsService metrics;

    public WebController(InvestigationQueryService query, ScenarioCatalog catalog, AdminApiController admin,
                         HealthService health, MetricsService metrics) {
        this.query = query;
        this.catalog = catalog;
        this.admin = admin;
        this.health = health;
        this.metrics = metrics;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/investigations";
    }

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("demoUsers", List.of("alice", "sam", "adam"));
        return "login";
    }

    @GetMapping("/simulator")
    public String simulator(Model model) {
        Map<String, List<Scenario>> byApp = catalog.all().stream()
                .collect(Collectors.groupingBy(Scenario::application, TreeMap::new, Collectors.toList()));
        model.addAttribute("byApp", byApp);
        model.addAttribute("active", "simulator");
        return "simulator";
    }

    @GetMapping("/investigations")
    public String investigations(@RequestParam(required = false) InvestigationState state,
                                 @RequestParam(required = false) String app, Model model) {
        List<Investigation> list = query.list(state, app, null, null);
        model.addAttribute("items", list);
        model.addAttribute("state", state);
        model.addAttribute("app", app);
        model.addAttribute("states", InvestigationState.values());
        model.addAttribute("anyOpen", list.stream().anyMatch(i -> i.getState().isOpen()));
        model.addAttribute("active", "investigations");
        return "investigations";
    }

    @GetMapping("/investigations/{id}")
    public String investigation(@PathVariable Long id, Model model) {
        InvestigationQueryService.Detail d = query.detail(id);
        model.addAttribute("d", d);
        model.addAttribute("inv", d.investigation());
        Map<FindingKind, List<Finding>> grouped = new EnumMap<>(FindingKind.class);
        for (FindingKind k : FindingKind.values()) grouped.put(k, new ArrayList<>());
        d.findings().forEach(f -> grouped.get(f.getKind()).add(f));
        model.addAttribute("confirmed", grouped.get(FindingKind.CONFIRMED));
        model.addAttribute("suspected", grouped.get(FindingKind.SUSPECTED));
        model.addAttribute("ruledOut", grouped.get(FindingKind.RULED_OUT));
        ActionProposal pending = d.proposals().stream().filter(p -> p.getStatus() == ProposalStatus.PROPOSED)
                .findFirst().orElse(null);
        model.addAttribute("pending", pending);
        model.addAttribute("canDecide", pending != null && CurrentUser.role().atLeast(pending.getRequiredRole()));
        model.addAttribute("pastProposals", d.proposals().stream().filter(p -> p != pending).toList());
        List<AuditEntry> timeline = d.audit().stream().filter(a -> TIMELINE_TYPES.contains(a.getType())).toList();
        model.addAttribute("timeline", timeline);
        model.addAttribute("myFeedback", d.feedback().stream()
                .filter(f -> f.getUsername().equals(CurrentUser.username())).findFirst().orElse(null));
        model.addAttribute("active", "investigations");
        return "investigation";
    }

    @GetMapping("/applications")
    public String applications(Model model) {
        model.addAttribute("apps", admin.list());
        model.addAttribute("active", "applications");
        return "applications";
    }

    @GetMapping("/health")
    public String healthPage(Model model) {
        model.addAttribute("h", health.health());
        model.addAttribute("m", metrics.metrics());
        model.addAttribute("active", "health");
        return "health";
    }
}
