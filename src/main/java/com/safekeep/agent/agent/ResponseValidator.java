package com.safekeep.agent.agent;

import com.safekeep.agent.apps.config.ActionConfig;
import com.safekeep.agent.approval.ApprovalService;
import com.safekeep.agent.common.Json;
import com.safekeep.agent.investigation.*;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Deterministic guardrails over the model's final answer (Spec Section 8.5, rules R-V1 to R-V7).
 * Lenient about formatting, strict about meaning.
 */
@Component
public class ResponseValidator {

    private final Json json;

    public ResponseValidator(Json json) {
        this.json = json;
    }

    public ValidationResult validate(String raw, ValidationContext ctx) {
        // R-V1: parse and check shape
        Map<String, Object> m;
        try {
            m = json.readMap(extractJson(raw));
        } catch (RuntimeException e) {
            return ValidationResult.invalid("Output is not a valid JSON object");
        }
        Object summaryObj = m.get("summary");
        if (!(summaryObj instanceof String summary) || summary.isBlank()) {
            return ValidationResult.invalid("'summary' must be a non-empty string");
        }
        Object findingsObj = m.get("findings");
        if (!(findingsObj instanceof List<?> rawFindings)) {
            return ValidationResult.invalid("'findings' must be a list of objects");
        }

        List<ValidatedFinding> findings = new ArrayList<>();
        for (Object o : rawFindings) {
            if (!(o instanceof Map<?, ?> f) || !(f.get("statement") instanceof String st) || st.isBlank()) {
                return ValidationResult.invalid("each finding needs a 'statement' string");
            }
            FindingKind kind = kind(f.get("kind"));
            // R-V2: keep only evidence IDs that exist
            List<String> ids = strings(f.get("evidenceIds")).stream()
                    .map(s -> s.replace("[", "").replace("]", "").trim().toUpperCase(Locale.ROOT))
                    .filter(ctx.evidence()::containsKey).distinct().toList();
            // R-V3: CONFIRMED needs at least one successful evidence item
            boolean downgraded = false;
            if (kind == FindingKind.CONFIRMED
                    && ids.stream().noneMatch(id -> ctx.evidence().get(id) == ToolCallStatus.OK)) {
                kind = FindingKind.SUSPECTED;
                downgraded = true;
            }
            findings.add(new ValidatedFinding(kind, st.trim(), ids, downgraded));
        }

        List<String> missing = new ArrayList<>(strings(m.get("missingInformation")));
        List<ValidatedRecommendation> recs = recommendations(m.get("recommendations"));

        // R-V4: proposed action must exist and be enabled; otherwise give manual instructions
        ValidatedProposal proposal = null;
        if (m.get("proposedAction") instanceof Map<?, ?> pa) {
            String name = pa.get("name") == null ? "" : String.valueOf(pa.get("name")).trim();
            if (!name.isBlank() && !name.equalsIgnoreCase("none") && !name.equalsIgnoreCase("null")) {
                Optional<ActionConfig> action = ctx.application().action(name);
                if (action.isEmpty() || !action.get().appliesTo(ctx.eventType())) {
                    // invented action, another application's action, or one that does not fit this event: ignore
                } else if (!ctx.actionEnabled().test(name)) {
                    recs.add(new ValidatedRecommendation(ApprovalService.manualText(action.get()),
                            recs.size() + 1, RecommendationKind.MANUAL));     // disabled: give the manual steps
                } else {
                    Map<String, Object> params = pa.get("params") instanceof Map<?, ?> pm ? castMap(pm) : Map.of();
                    String reason = pa.get("reason") == null ? null : String.valueOf(pa.get("reason"));
                    proposal = new ValidatedProposal(action.get(), params, reason);
                }
            }
        }

        // R-V5: never return nothing
        Resolution resolution = "UNRESOLVED".equalsIgnoreCase(String.valueOf(m.get("resolution")))
                ? Resolution.UNRESOLVED : Resolution.UNKNOWN;                     // R-V6
        if (findings.isEmpty() && missing.isEmpty()) {
            missing.add("Cause could not be determined");
            resolution = Resolution.UNKNOWN;
        }

        // R-V8: only propose a fix when the evidence shows the problem is still present
        if (proposal != null && resolution != Resolution.UNRESOLVED) {
            recs.add(new ValidatedRecommendation("Proposed action '" + proposal.action().name()
                    + "' was dropped: the evidence does not show the problem is still present.",
                    recs.size() + 1, RecommendationKind.MANUAL));
            proposal = null;
        }

        // R-V7: a question for the user means we wait for a reply
        String question = m.get("questionForUser") instanceof String q && !q.isBlank()
                && !q.equalsIgnoreCase("null") ? q.trim() : null;
        InvestigationState next = question != null ? InvestigationState.WAITING_FOR_INFO : InvestigationState.COMPLETED;

        return ValidationResult.ok(new ValidatedOutput(summary.trim(), findings, missing, recs, proposal,
                question, resolution, next));
    }

    private static String extractJson(String raw) {
        if (raw == null) return "";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : raw;
    }

    private static FindingKind kind(Object o) {
        String s = String.valueOf(o).trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return FindingKind.valueOf(s);
        } catch (IllegalArgumentException e) {
            return FindingKind.SUSPECTED;
        }
    }

    private static List<String> strings(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) {
            return l.stream().filter(Objects::nonNull).map(String::valueOf).filter(s -> !s.isBlank()).toList();
        }
        String s = String.valueOf(o);
        if (s.isBlank() || s.equalsIgnoreCase("null")) return List.of();
        return Arrays.stream(s.split("[,\\s]+")).filter(x -> !x.isBlank()).toList();
    }

    private static List<ValidatedRecommendation> recommendations(Object o) {
        List<ValidatedRecommendation> out = new ArrayList<>();
        if (!(o instanceof List<?> list)) return out;
        int i = 1;
        for (Object r : list) {
            if (r instanceof Map<?, ?> rm && rm.get("text") != null && !String.valueOf(rm.get("text")).isBlank()) {
                int p = rm.get("priority") instanceof Number n ? n.intValue() : i;
                out.add(new ValidatedRecommendation(String.valueOf(rm.get("text")).trim(), p, RecommendationKind.MANUAL));
            } else if (r instanceof String s && !s.isBlank()) {
                out.add(new ValidatedRecommendation(s.trim(), i, RecommendationKind.MANUAL));
            }
            i++;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
