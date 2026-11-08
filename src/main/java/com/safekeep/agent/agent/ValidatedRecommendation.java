package com.safekeep.agent.agent;

import com.safekeep.agent.investigation.RecommendationKind;

public record ValidatedRecommendation(String text, int priority, RecommendationKind kind) { }
