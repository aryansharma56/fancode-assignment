package com.fancode.mockpoll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The scripted, deterministic match served by the mock. Responses depend only on the current
 * step, which moves only when told to (admin endpoint or the optional auto-advance timer).
 */
@Component
public class Timeline {

    /**
     * One step of the script.
     *
     * @param score          snapshot JSON, or null when scoreError is set
     * @param commentary     full list of commentary items served at this step
     * @param scoreError     HTTP status to return for /score instead of data (e.g. 503)
     * @param commentaryError HTTP status to return for /commentary instead of data
     */
    public record Step(String name, JsonNode score, List<JsonNode> commentary,
                       Integer scoreError, Integer commentaryError) {
    }

    private final String providerMatchId;
    private final List<Step> steps;
    private final AtomicInteger current = new AtomicInteger(0); // 0-based index

    public Timeline(@Value("${mock.timeline}") Resource resource, ObjectMapper mapper) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = mapper.readTree(in);
            this.providerMatchId = root.path("providerMatchId").asText();
            this.steps = parseSteps(root);
        }
        if (steps.isEmpty()) {
            throw new IllegalStateException("timeline has no steps");
        }
    }

    private static List<Step> parseSteps(JsonNode root) {
        Map<String, JsonNode> balls = new LinkedHashMap<>();
        for (JsonNode ball : root.path("balls")) {
            ObjectNode item = ball.deepCopy();
            String key = item.remove("key").asText();
            balls.put(key, item);
        }
        List<Step> steps = new ArrayList<>();
        for (JsonNode step : root.path("steps")) {
            List<JsonNode> items = new ArrayList<>();
            for (JsonNode key : step.path("commentary")) {
                JsonNode item = balls.get(key.asText());
                if (item == null) {
                    throw new IllegalStateException("unknown ball key " + key.asText());
                }
                items.add(item);
            }
            steps.add(new Step(
                    step.path("name").asText(),
                    step.hasNonNull("score") ? step.get("score") : null,
                    List.copyOf(items),
                    step.hasNonNull("scoreError") ? step.get("scoreError").asInt() : null,
                    step.hasNonNull("commentaryError") ? step.get("commentaryError").asInt() : null));
        }
        return List.copyOf(steps);
    }

    public String providerMatchId() {
        return providerMatchId;
    }

    public Step currentStep() {
        return steps.get(current.get());
    }

    /** 1-based number of the current step. */
    public int currentStepNumber() {
        return current.get() + 1;
    }

    public int stepCount() {
        return steps.size();
    }

    public List<Step> steps() {
        return steps;
    }

    /** Moves to the next step; stays on the last step once reached. Returns the new 1-based number. */
    public int advance() {
        return current.updateAndGet(i -> Math.min(i + 1, steps.size() - 1)) + 1;
    }

    /** Jumps to a 1-based step number. */
    public void jumpTo(int stepNumber) {
        if (stepNumber < 1 || stepNumber > steps.size()) {
            throw new IllegalArgumentException("step must be between 1 and " + steps.size());
        }
        current.set(stepNumber - 1);
    }
}
