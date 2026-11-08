package com.safekeep.agent.tools;

import com.safekeep.agent.investigation.ToolCallStatus;

import java.util.Map;

public record ToolCallOutcome(String evidenceId, String toolName, ToolCallStatus status, String errorCode,
                              String message, Map<String, Object> result) {
    public boolean ok() {
        return status == ToolCallStatus.OK;
    }
}
