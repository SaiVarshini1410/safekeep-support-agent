package com.safekeep.agent.api;

import com.safekeep.agent.common.NotFoundException;
import com.safekeep.agent.intake.IntakeException;
import com.safekeep.agent.investigation.IllegalStateTransitionException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** One error format for the whole API (Spec Section 11). */
@RestControllerAdvice(basePackages = "com.safekeep.agent.api")
public class ApiErrorHandler {

    @ExceptionHandler(IntakeException.class)
    public ResponseEntity<Map<String, Object>> intake(IntakeException e) {
        Map<String, Object> body = body(e.getCode(), e.getMessage(), e.getHint());
        if (!e.getFieldErrors().isEmpty()) body.put("fields", e.getFieldErrors());
        return ResponseEntity.status(e.getStatus()).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> denied(AccessDeniedException e) {
        return ResponseEntity.status(403).body(body("FORBIDDEN", e.getMessage(), null));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return ResponseEntity.status(404).body(body("NOT_FOUND", e.getMessage(), null));
    }

    @ExceptionHandler({IllegalStateException.class, IllegalStateTransitionException.class})
    public ResponseEntity<Map<String, Object>> conflict(RuntimeException e) {
        return ResponseEntity.status(409).body(body("CONFLICT", e.getMessage(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(400).body(body("BAD_REQUEST", e.getMessage(), null));
    }

    static Map<String, Object> body(String code, String message, String hint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", code);
        m.put("message", message);
        if (hint != null) m.put("hint", hint);
        return m;
    }
}
