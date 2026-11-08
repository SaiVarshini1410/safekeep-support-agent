package com.safekeep.agent.tools;

import com.safekeep.agent.apps.config.ParamConfig;

import java.util.*;

/** Gateway step G4: validates and safely coerces tool arguments against the configured schema. */
public final class ArgumentValidator {

    private ArgumentValidator() { }

    public static Map<String, Object> validate(List<ParamConfig> params, Map<String, Object> args) {
        Map<String, Object> in = args == null ? Map.of() : args;
        Set<String> known = new HashSet<>();
        params.forEach(p -> known.add(p.name()));
        for (String k : in.keySet()) {
            if (!known.contains(k)) {
                throw new InvalidArgumentException("Unknown argument '" + k + "'. Allowed: " + known);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (ParamConfig p : params) {
            Object value = in.get(p.name());
            if (value == null || String.valueOf(value).isBlank()) {
                if (p.defaultValue() != null) {
                    value = p.defaultValue();
                } else if (p.required()) {
                    throw new InvalidArgumentException("Missing required argument '" + p.name() + "'");
                } else {
                    continue;
                }
            }
            out.put(p.name(), coerce(p, value));
        }
        return out;
    }

    private static Object coerce(ParamConfig p, Object value) {
        switch (p.type()) {
            case "integer" -> {
                int n;
                if (value instanceof Number num) {
                    if (num.doubleValue() != Math.rint(num.doubleValue())) {
                        throw new InvalidArgumentException("'" + p.name() + "' must be a whole number");
                    }
                    n = num.intValue();
                } else {
                    try {
                        n = Integer.parseInt(String.valueOf(value).trim());
                    } catch (NumberFormatException e) {
                        throw new InvalidArgumentException("'" + p.name() + "' must be an integer, got '" + value + "'");
                    }
                }
                if ((p.min() != null && n < p.min()) || (p.max() != null && n > p.max())) {
                    throw new InvalidArgumentException("'" + p.name() + "' must be between "
                            + p.min() + " and " + p.max() + ", got " + n);
                }
                return n;
            }
            case "boolean" -> {
                String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
                if (!s.equals("true") && !s.equals("false")) {
                    throw new InvalidArgumentException("'" + p.name() + "' must be true or false");
                }
                return Boolean.parseBoolean(s);
            }
            default -> {
                String s = String.valueOf(value).trim();
                if (!p.allowed().isEmpty()) {
                    return p.allowed().stream().filter(a -> a.equalsIgnoreCase(s)).findFirst()
                            .orElseThrow(() -> new InvalidArgumentException("'" + p.name()
                                    + "' must be one of " + p.allowed() + ", got '" + s + "'"));
                }
                return s;
            }
        }
    }
}
