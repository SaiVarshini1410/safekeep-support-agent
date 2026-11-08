package com.safekeep.agent.intake;

import java.util.List;

/** A rejected event, carrying the HTTP status and an actionable message (NFR-USAB-01). */
public class IntakeException extends RuntimeException {
    private final int status;
    private final String code;
    private final String hint;
    private final List<String> fieldErrors;

    public IntakeException(int status, String code, String message, String hint, List<String> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.hint = hint;
        this.fieldErrors = fieldErrors == null ? List.of() : fieldErrors;
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
    public String getHint() { return hint; }
    public List<String> getFieldErrors() { return fieldErrors; }
}
