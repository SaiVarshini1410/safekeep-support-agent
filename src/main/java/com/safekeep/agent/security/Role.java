package com.safekeep.agent.security;

/** Ordered from least to most privileged (decision D2). */
public enum Role {
    APP_USER,
    SUPPORT_ENGINEER,
    ADMIN;

    public boolean atLeast(Role required) {
        return this.ordinal() >= required.ordinal();
    }
}
