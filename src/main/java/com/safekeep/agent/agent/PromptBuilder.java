package com.safekeep.agent.agent;

import com.safekeep.agent.apps.RegisteredApplication;
import com.safekeep.agent.apps.config.ActionConfig;
import com.safekeep.agent.apps.config.ParamConfig;
import com.safekeep.agent.apps.config.ToolConfig;
import com.safekeep.agent.investigation.Finding;
import com.safekeep.agent.investigation.Investigation;
import com.safekeep.agent.investigation.Message;
import com.safekeep.agent.llm.LlmToolSpec;
import org.springframework.stereotype.Component;

import java.util.*;

/** Builds what the model sees (Spec Section 8.2). Never includes API keys or other secrets. */
@Component
public class PromptBuilder {

    public String system(RegisteredApplication app, List<ActionConfig> actions) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a support investigator for the application \"").append(app.name()).append("\". ")
          .append(app.config().description() == null ? "" : app.config().description()).append("\n\n")
          .append("Investigate the event using ONLY the provided tools. Call one tool at a time. ")
          .append("Every tool result starts with an evidence ID such as [E1].\n\n")
          .append("Rules:\n")
          .append("- Base every statement on tool results and cite their evidence IDs.\n")
          .append("- CONFIRMED means directly measured in a tool result. SUSPECTED means your inference. ")
          .append("RULED_OUT means the evidence excludes that cause.\n")
          .append("- Never claim that an action was executed. You can only propose one of the actions listed below; a human must approve it.\n")
          .append("- If information is missing, say what is missing and what to check next.\n")
          .append("- When you have enough evidence, stop calling tools and reply with a short plain-text summary.\n");
        if (!app.config().thresholds().isEmpty()) {
            sb.append("\nConfigured thresholds: ").append(app.config().thresholds()).append('\n');
        }
        sb.append("\nProposable actions (not callable as tools):\n");
        if (actions.isEmpty()) {
            sb.append("- none\n");
        }
        for (ActionConfig a : actions) {
            sb.append("- ").append(a.name()).append(": ").append(a.description());
            if (!a.parameters().isEmpty()) {
                sb.append(" (params: ").append(a.parameters().stream().map(ParamConfig::name).toList()).append(')');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public String kickoff(Investigation inv, Object eventDetails, List<Message> context, List<Finding> prior) {
        StringBuilder sb = new StringBuilder();
        sb.append("Event received: ").append(inv.getEventType()).append(" on ").append(inv.getResource())
          .append(". Details: ").append(eventDetails).append(". ")
          .append("This event has been reported ").append(inv.getEventCount()).append(" time(s).");
        if (inv.getRecurrenceOfId() != null) {
            sb.append(" The same issue occurred before (INV-").append(inv.getRecurrenceOfId()).append(").");
        }
        if (!prior.isEmpty()) {
            sb.append("\n\nPrevious findings:\n");
            prior.forEach(f -> sb.append("- ").append(f.getKind()).append(": ").append(f.getStatement()).append('\n'));
        }
        if (!context.isEmpty()) {
            sb.append("\nConversation with the user so far:\n");
            context.forEach(m -> sb.append("- ").append(m.getAuthor()).append(" (").append(m.getType()).append("): ")
                    .append(m.getBody()).append('\n'));
        }
        sb.append("\nStart investigating.");
        return sb.toString();
    }

    public String budgetExhausted(int max) {
        return "You have used all " + max + " available tool calls. Do not call more tools.";
    }

    public String finalInstruction() {
        return """
                Now write your final answer as ONE JSON object and nothing else, with these fields:
                {
                  "summary": "2-4 sentences in plain language",
                  "findings": [{"kind": "CONFIRMED or SUSPECTED or RULED_OUT", "statement": "...", "evidenceIds": ["E1"]}],
                  "missingInformation": ["what could not be determined"],
                  "recommendations": [{"text": "next step", "priority": 1}],
                  "proposedAction": {"name": "one of the proposable actions", "params": {}, "reason": "..."} or null,
                  "questionForUser": "one question" or null,
                  "resolution": "UNRESOLVED" or "UNKNOWN"
                }
                Use only evidence IDs that appear in the tool results.
                Use "UNRESOLVED" if the evidence shows the problem is still present, otherwise "UNKNOWN".
                Set questionForUser only if you cannot determine the cause and the user could help.
                """;
    }

    public String repair(String error) {
        return "Your previous answer was invalid: " + error + ". Reply again with only the JSON object described above.";
    }

    public String answerSystem(Investigation inv, List<Finding> findings, String evidenceDigest) {
        StringBuilder sb = new StringBuilder("You answer questions about investigation ").append(inv.displayId())
                .append(" (").append(inv.getEventType()).append(" on ").append(inv.getResource()).append(").\n")
                .append("Answer briefly using only the summary, findings and evidence below. ")
                .append("If they do not contain the answer, say so. Never claim actions were executed.\n\n")
                .append("Summary: ").append(inv.getSummary()).append("\nFindings:\n");
        findings.forEach(f -> sb.append("- ").append(f.getKind()).append(": ").append(f.getStatement())
                .append(" ").append(f.getEvidenceIdList()).append('\n'));
        sb.append("\nEvidence:\n").append(evidenceDigest);
        return sb.toString();
    }

    /** JSON schema for each tool's parameters, as the model expects. */
    public List<LlmToolSpec> toolSpecs(List<ToolConfig> tools) {
        List<LlmToolSpec> specs = new ArrayList<>();
        for (ToolConfig t : tools) {
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (ParamConfig p : t.parameters()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("type", p.type());
                if (!p.allowed().isEmpty()) s.put("enum", p.allowed());
                StringBuilder d = new StringBuilder();
                if (p.min() != null || p.max() != null) d.append("range ").append(p.min()).append("-").append(p.max()).append(". ");
                if (p.defaultValue() != null) d.append("default ").append(p.defaultValue()).append('.');
                if (!d.isEmpty()) s.put("description", d.toString().trim());
                props.put(p.name(), s);
                if (p.required()) required.add(p.name());
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", required);
            specs.add(new LlmToolSpec(t.name(), t.description(), schema));
        }
        return specs;
    }
}
