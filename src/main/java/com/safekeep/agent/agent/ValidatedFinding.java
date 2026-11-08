package com.safekeep.agent.agent;

import com.safekeep.agent.investigation.FindingKind;

import java.util.List;

public record ValidatedFinding(FindingKind kind, String statement, List<String> evidenceIds, boolean downgraded) { }
