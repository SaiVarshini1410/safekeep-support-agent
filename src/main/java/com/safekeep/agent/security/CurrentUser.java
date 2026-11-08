package com.safekeep.agent.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who is calling, and their highest role. */
public final class CurrentUser {

    private CurrentUser() { }

    public static String username() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? "anonymous" : a.getName();
    }

    public static Role role() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        Role best = Role.APP_USER;
        if (a == null) return best;
        for (GrantedAuthority g : a.getAuthorities()) {
            String name = g.getAuthority().replace("ROLE_", "");
            for (Role r : Role.values()) {
                if (r.name().equals(name) && r.atLeast(best)) best = r;
            }
        }
        return best;
    }
}
