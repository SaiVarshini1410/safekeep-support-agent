package com.safekeep.agent.investigation;

import java.time.Instant;

/** One step shown on the live timeline (FR-INV-03). */
public record ProgressEvent(Long investigationId, String type, String message, Instant at) { }
