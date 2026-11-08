package com.safekeep.agent.apps;

import com.safekeep.agent.common.Json;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulated application backed by JSON seed data (assumption A1, A5).
 * seed/<app>/baseline.json holds normal values; seed/<app>/<scenario>.json overrides them.
 * Tool results are chosen by selectors such as "metric=memory" with "*" as fallback.
 * Actions may have scripted effects that change later tool results.
 */
public abstract class SeedDataAdapter implements AppAdapter {

    private static final String BASELINE = "baseline";

    private final String appId;
    private final Json json;
    private final ApplicationRegistry registry;
    private final Map<String, Map<String, Map<String, Object>>> states = new ConcurrentHashMap<>();

    protected SeedDataAdapter(String appId, Json json, ApplicationRegistry registry) {
        this.appId = appId;
        this.json = json;
        this.registry = registry;
    }

    @Override
    public String applicationId() {
        return appId;
    }

    @Override
    public synchronized Map<String, Object> executeTool(String scenarioId, String tool, Map<String, Object> args) {
        if ("search_runbook".equals(tool)) {
            return searchRunbook(String.valueOf(args.getOrDefault("query", "")));
        }
        Map<String, Object> selectors = state(scenarioId).get(tool);
        if (selectors == null) {
            return Map.of("result", "No data recorded for this tool");
        }
        Object match = select(selectors, args);
        if (!(match instanceof Map<?, ?> m)) {
            return Map.of("result", "No data for these arguments");
        }
        if (m.containsKey("_error")) {
            throw new AdapterException(String.valueOf(m.get("_error")));
        }
        Map<String, Object> result = castMap(json.deepCopy(m));
        filterLines(result, args);
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> executeAction(String scenarioId, String action, Map<String, Object> args) {
        Map<String, Object> scenario = load(key(scenarioId));
        Map<String, Object> actions = (Map<String, Object>) scenario.getOrDefault("actions", Map.of());
        Object def = actions.get(action);
        if (!(def instanceof Map<?, ?> d)) {
            return Map.of("status", "OK", "message", "Action " + action + " completed");
        }
        if (d.containsKey("error")) {
            throw new AdapterException(String.valueOf(d.get("error")));
        }
        Object effects = d.get("effects");
        if (effects instanceof Map<?, ?> eff) {
            Map<String, Map<String, Object>> st = state(scenarioId);
            eff.forEach((tool, sel) -> {
                Map<String, Object> current = st.computeIfAbsent(String.valueOf(tool), t -> new LinkedHashMap<>());
                Map<String, Object> merged = new LinkedHashMap<>(castMap(json.deepCopy(sel)));
                current.forEach(merged::putIfAbsent);
                st.put(String.valueOf(tool), merged);
            });
        }
        Object result = d.get("result");
        return result instanceof Map<?, ?> r ? castMap(json.deepCopy(r)) : Map.of("status", "OK");
    }

    @Override
    public synchronized void reset(String scenarioId) {
        states.remove(key(scenarioId));
    }

    // ---------------------------------------------------------------

    private static String key(String scenarioId) {
        return scenarioId == null || scenarioId.isBlank() ? BASELINE : scenarioId;
    }

    /** Scenario selectors first, then baseline selectors the scenario does not override. */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> state(String scenarioId) {
        return states.computeIfAbsent(key(scenarioId), k -> {
            Map<String, Map<String, Object>> st = new LinkedHashMap<>();
            Map<String, Object> baseTools = (Map<String, Object>) load(BASELINE).getOrDefault("tools", Map.of());
            Map<String, Object> scnTools = BASELINE.equals(k) ? Map.of()
                    : (Map<String, Object>) load(k).getOrDefault("tools", Map.of());
            Set<String> names = new LinkedHashSet<>(baseTools.keySet());
            names.addAll(scnTools.keySet());
            for (String tool : names) {
                Map<String, Object> merged = new LinkedHashMap<>();
                if (scnTools.get(tool) instanceof Map<?, ?> s) merged.putAll(castMap(json.deepCopy(s)));
                if (baseTools.get(tool) instanceof Map<?, ?> b) castMap(json.deepCopy(b)).forEach(merged::putIfAbsent);
                st.put(tool, merged);
            }
            return st;
        });
    }

    private Map<String, Object> load(String name) {
        ClassPathResource res = new ClassPathResource("seed/" + appId + "/" + name + ".json");
        if (!res.exists()) return Map.of();
        try (InputStream in = res.getInputStream()) {
            return json.readMap(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed data " + res.getPath(), e);
        }
    }

    private static Object select(Map<String, Object> selectors, Map<String, Object> args) {
        for (Map.Entry<String, Object> e : selectors.entrySet()) {
            if ("*".equals(e.getKey())) continue;
            boolean all = true;
            for (String cond : e.getKey().split(",")) {
                String[] kv = cond.split("=", 2);
                Object actual = args.get(kv[0].trim());
                if (kv.length < 2 || actual == null || !String.valueOf(actual).equalsIgnoreCase(kv[1].trim())) {
                    all = false;
                    break;
                }
            }
            if (all) return e.getValue();
        }
        return selectors.get("*");
    }

    /** Generic log filtering for any result that has "lines". */
    @SuppressWarnings("unchecked")
    private static void filterLines(Map<String, Object> result, Map<String, Object> args) {
        if (!(result.get("lines") instanceof List<?> lines)) return;
        String contains = String.valueOf(args.getOrDefault("contains", "")).toLowerCase(Locale.ROOT);
        String level = String.valueOf(args.getOrDefault("level", "ALL")).toUpperCase(Locale.ROOT);
        List<Object> filtered = new ArrayList<>();
        for (Object l : lines) {
            String s = String.valueOf(l);
            boolean okText = contains.isBlank() || "null".equals(contains) || s.toLowerCase(Locale.ROOT).contains(contains);
            boolean okLevel = "ALL".equals(level) || "NULL".equals(level) || s.contains(level);
            if (okText && okLevel) filtered.add(s);
        }
        result.put("lines", filtered);
        result.put("matchedLines", filtered.size());
    }

    private Map<String, Object> searchRunbook(String query) {
        List<String> words = Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\W+"))
                .filter(w -> w.length() > 2).toList();
        List<Map<String, Object>> hits = new ArrayList<>();
        List<String> paths = registry.find(appId).map(a -> a.config().runbooks()).orElse(List.of());
        for (String path : paths) {
            ClassPathResource res = new ClassPathResource(path);
            if (!res.exists()) continue;
            try (InputStream in = res.getInputStream()) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                for (String para : text.split("\\n\\s*\\n")) {
                    String lower = para.toLowerCase(Locale.ROOT);
                    long score = words.stream().filter(lower::contains).count();
                    if (score > 0) hits.add(Map.of("runbook", path.substring(path.lastIndexOf('/') + 1),
                            "excerpt", para.trim(), "score", score));
                }
            } catch (IOException ignored) {
                // a missing runbook is not fatal
            }
        }
        hits.sort((a, b) -> Long.compare((Long) b.get("score"), (Long) a.get("score")));
        List<Map<String, Object>> top = hits.stream().limit(3)
                .map(h -> Map.<String, Object>of("runbook", h.get("runbook"), "excerpt", h.get("excerpt"))).toList();
        return Map.of("query", query, "matches", top);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }
}
