package com.safekeep.agent.web;

import com.safekeep.agent.common.Json;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Formatting helpers used by the templates as ${@fmt...}. */
@Component("fmt")
public class Fmt {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Json json;

    public Fmt(Json json) {
        this.json = json;
    }

    public String time(Instant i) { return i == null ? "" : TIME.format(i); }

    public String dateTime(Instant i) { return i == null ? "" : DATE_TIME.format(i); }

    public String ago(Instant i) {
        if (i == null) return "";
        long s = Duration.between(i, Instant.now()).toSeconds();
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86400) return (s / 3600) + "h ago";
        return (s / 86400) + "d ago";
    }

    public String pretty(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json.readMap(raw));
        } catch (RuntimeException e) {
            return raw;
        }
    }

    public String label(Object enumValue) {
        if (enumValue == null) return "";
        String s = enumValue.toString().replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
