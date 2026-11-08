package com.safekeep.agent.api;

import com.safekeep.agent.intake.EventIntakeService;
import com.safekeep.agent.intake.EventRequest;
import com.safekeep.agent.intake.IntakeResult;
import com.safekeep.agent.intake.IntakeStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** POST /api/events: how applications report problems. Authenticated by API key. */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventIntakeService intake;

    public EventController(EventIntakeService intake) {
        this.intake = intake;
    }

    @PostMapping
    public ResponseEntity<IntakeResult> receive(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                                @RequestBody(required = false) EventRequest body) {
        IntakeResult r = intake.receive(apiKey, body, null);
        return ResponseEntity.status(r.status() == IntakeStatus.CREATED ? 202 : 200).body(r);
    }
}
