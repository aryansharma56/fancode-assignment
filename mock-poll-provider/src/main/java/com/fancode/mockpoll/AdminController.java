package com.fancode.mockpoll;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Test-only controls that move the scripted match forward deterministically. */
@RestController
@RequestMapping("/provider/admin")
public class AdminController {

    private final Timeline timeline;

    public AdminController(Timeline timeline) {
        this.timeline = timeline;
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        List<String> names = IntStream.range(0, timeline.stepCount())
                .mapToObj(i -> (i + 1) + ": " + timeline.steps().get(i).name())
                .toList();
        return Map.of(
                "providerMatchId", timeline.providerMatchId(),
                "currentStep", timeline.currentStepNumber(),
                "currentStepName", timeline.currentStep().name(),
                "steps", names);
    }

    @PostMapping("/step")
    public Map<String, Object> advance() {
        timeline.advance();
        return state();
    }

    @PutMapping("/step/{n}")
    public Map<String, Object> jump(@PathVariable int n) {
        timeline.jumpTo(n);
        return state();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        timeline.jumpTo(1);
        return state();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badStep(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
