package com.safekeep.agent.support;

import java.time.*;

/** Fixed clock that tests can move forward (e.g. proposal expiry). */
public class MutableClock extends Clock {

    public static final Instant START = Instant.parse("2026-11-07T10:00:00Z");
    private volatile Instant now = START;

    public void reset() { now = START; }

    public void advance(Duration d) { now = now.plus(d); }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }

    @Override public Clock withZone(ZoneId zone) { return this; }

    @Override public Instant instant() { return now; }
}
