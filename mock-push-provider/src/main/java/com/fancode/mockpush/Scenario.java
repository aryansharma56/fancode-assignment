package com.fancode.mockpush;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A scripted list of webhook deliveries, sent strictly in file order. Duplicates, out-of-order
 * and delayed deliveries are written into the script, so every run sends exactly the same thing.
 */
public record Scenario(String name, String description, List<Delivery> deliveries) {

    /** @param note what this delivery demonstrates; printed while running */
    public record Delivery(String note, JsonNode body) {
    }

    /** Loads a bundled scenario by name (scenarios/NAME.json) or any JSON file by path. */
    public static Scenario load(String nameOrPath, ObjectMapper mapper) throws IOException {
        Resource resource = new FileSystemResource(nameOrPath);
        if (!resource.exists()) {
            resource = new ClassPathResource("scenarios/" + nameOrPath + ".json");
        }
        if (!resource.exists()) {
            throw new IllegalArgumentException("No scenario named or at '" + nameOrPath + "'");
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = mapper.readTree(in);
            List<Delivery> deliveries = new ArrayList<>();
            for (JsonNode d : root.path("deliveries")) {
                deliveries.add(new Delivery(d.path("note").asText(), d.get("body")));
            }
            return new Scenario(root.path("name").asText(), root.path("description").asText(), List.copyOf(deliveries));
        }
    }
}
