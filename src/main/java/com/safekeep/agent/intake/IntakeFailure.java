package com.safekeep.agent.intake;

import java.time.Instant;

public record IntakeFailure(Instant at, String application, String reason, String excerpt) { }
