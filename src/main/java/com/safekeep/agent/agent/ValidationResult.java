package com.safekeep.agent.agent;

public record ValidationResult(boolean valid, String error, ValidatedOutput output) {
    public static ValidationResult invalid(String error) { return new ValidationResult(false, error, null); }
    public static ValidationResult ok(ValidatedOutput out) { return new ValidationResult(true, null, out); }
}
