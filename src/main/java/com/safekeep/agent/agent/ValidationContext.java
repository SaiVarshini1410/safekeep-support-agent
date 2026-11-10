package com.safekeep.agent.agent;

import com.safekeep.agent.apps.RegisteredApplication;
import com.safekeep.agent.investigation.ToolCallStatus;

import java.util.Map;
import java.util.function.Predicate;

/** What the validator needs to know: recorded evidence and which actions are available. */
public record ValidationContext(Map<String, ToolCallStatus> evidence, RegisteredApplication application,
                                Predicate<String> actionEnabled, String eventType) { }
