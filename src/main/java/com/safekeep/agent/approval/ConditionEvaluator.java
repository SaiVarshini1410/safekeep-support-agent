package com.safekeep.agent.approval;

import java.util.Map;

/**
 * Evaluates simple verification conditions such as
 * "current < thresholds.memoryPercent" or "stale == 0 && status == SUCCESS".
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() { }

    public static boolean evaluate(String condition, Map<String, Object> result, Map<String, Double> thresholds) {
        if (condition == null || condition.isBlank()) return false;
        for (String clause : condition.split("&&")) {
            if (!clause(clause.trim(), result, thresholds)) return false;
        }
        return true;
    }

    private static boolean clause(String c, Map<String, Object> result, Map<String, Double> thresholds) {
        for (String op : new String[]{"<=", ">=", "==", "!=", "<", ">"}) {
            int i = c.indexOf(op);
            if (i > 0) {
                Object left = resolve(c.substring(0, i).trim(), result, thresholds);
                Object right = resolve(c.substring(i + op.length()).trim(), result, thresholds);
                return compare(left, right, op);
            }
        }
        throw new IllegalArgumentException("Unsupported condition: " + c);
    }

    @SuppressWarnings("unchecked")
    private static Object resolve(String token, Map<String, Object> result, Map<String, Double> thresholds) {
        if (token.startsWith("thresholds.")) return thresholds.get(token.substring("thresholds.".length()));
        if (token.startsWith("'") || token.startsWith("\"")) return token.substring(1, token.length() - 1);
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException ignored) { }
        Object cur = result;
        for (String part : token.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m) || !m.containsKey(part)) {
                return cur == result ? token : null;   // bare word = string literal
            }
            cur = ((Map<String, Object>) m).get(part);
        }
        return cur;
    }

    private static boolean compare(Object l, Object r, String op) {
        if (l == null || r == null) return false;
        Double ln = num(l), rn = num(r);
        if (ln != null && rn != null) {
            return switch (op) {
                case "<" -> ln < rn;
                case "<=" -> ln <= rn;
                case ">" -> ln > rn;
                case ">=" -> ln >= rn;
                case "==" -> ln.equals(rn);
                default -> !ln.equals(rn);
            };
        }
        boolean eq = String.valueOf(l).equalsIgnoreCase(String.valueOf(r));
        return switch (op) {
            case "==" -> eq;
            case "!=" -> !eq;
            default -> false;
        };
    }

    private static Double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
