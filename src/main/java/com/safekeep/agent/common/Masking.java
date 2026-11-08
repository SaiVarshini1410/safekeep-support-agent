package com.safekeep.agent.common;

import java.util.Collection;

/** Removes secrets from text before it is logged, stored or shown (NFR-SEC-01). */
public final class Masking {
    private Masking() { }

    public static String mask(String text, Collection<String> secrets) {
        if (text == null) return null;
        String out = text;
        for (String s : secrets) {
            if (s != null && s.length() >= 4) out = out.replace(s, "****");
        }
        return out;
    }
}
