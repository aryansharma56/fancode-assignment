package com.fancode.mockpoll;

import com.fancode.mockpoll.Timeline.Step;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Provider B's public poll API. */
@RestController
@RequestMapping("/provider/matches/{id}")
public class ProviderController {

    private static final String PROVIDER = "provider-b";

    private final Timeline timeline;

    public ProviderController(Timeline timeline) {
        this.timeline = timeline;
    }

    @GetMapping("/score")
    public ResponseEntity<Map<String, Object>> score(@PathVariable String id) {
        if (!timeline.providerMatchId().equals(id)) {
            return notFound(id);
        }
        Step step = timeline.currentStep();
        if (step.scoreError() != null) {
            return error(step.scoreError());
        }
        Map<String, Object> body = envelope();
        body.put("snapshot", step.score());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/commentary")
    public ResponseEntity<Map<String, Object>> commentary(@PathVariable String id) {
        if (!timeline.providerMatchId().equals(id)) {
            return notFound(id);
        }
        Step step = timeline.currentStep();
        if (step.commentaryError() != null) {
            return error(step.commentaryError());
        }
        Map<String, Object> body = envelope();
        body.put("items", step.commentary());
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> envelope() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", PROVIDER);
        body.put("providerMatchId", timeline.providerMatchId());
        return body;
    }

    private static ResponseEntity<Map<String, Object>> error(int status) {
        return ResponseEntity.status(status).body(Map.of("error", "temporarily unavailable"));
    }

    private static ResponseEntity<Map<String, Object>> notFound(String id) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown match " + id));
    }
}
