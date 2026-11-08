package com.safekeep.agent.intake;

public record IntakeResult(IntakeStatus status, Long investigationId, String investigation) { }
