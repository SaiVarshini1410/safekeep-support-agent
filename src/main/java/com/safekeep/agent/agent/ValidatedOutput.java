package com.safekeep.agent.agent;

import com.safekeep.agent.investigation.InvestigationState;
import com.safekeep.agent.investigation.Resolution;

import java.util.ArrayList;
import java.util.List;

public record ValidatedOutput(String summary, List<ValidatedFinding> findings, List<String> missingInformation,
                              List<ValidatedRecommendation> recommendations, ValidatedProposal proposal,
                              String questionForUser, Resolution resolution, InvestigationState nextState) {

    public ValidatedOutput withMissingIfEmpty(String note) {
        if (!missingInformation.isEmpty()) return this;
        List<String> m = new ArrayList<>(missingInformation);
        m.add(note);
        return new ValidatedOutput(summary, findings, m, recommendations, proposal, questionForUser, resolution, nextState);
    }
}
