package com.safekeep.agent.agent;

import com.safekeep.agent.apps.config.ActionConfig;

import java.util.Map;

public record ValidatedProposal(ActionConfig action, Map<String, Object> params, String reason) { }
