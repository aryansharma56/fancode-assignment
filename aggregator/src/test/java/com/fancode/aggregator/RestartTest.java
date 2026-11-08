package com.fancode.aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import static com.fancode.aggregator.Events.ball;
import static com.fancode.aggregator.Events.push;
import static com.fancode.aggregator.Events.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real application twice against the same database. State written through the first
 * process must be served unchanged by the second. Extends the base only to reuse its container
 * and to start from clean tables.
 */
class RestartTest extends IntegrationTestBase {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void storedStateSurvivesRestart() throws Exception {
        JsonNode scoreBefore;
        JsonNode commentaryBefore;

        try (ConfigurableApplicationContext first = startApp()) {
            RestClient http = client(first);
            post(http, push("r1", snapshot(1, 1, 4, 0, "0.4"), ball("b1", 1, 1, "four")));
            post(http, push("r2", snapshot(5, 1, "innings_break", 150, 6, "20.0"), ball("b2", 2, 1, "out")));
            post(http, push("r3", null, ball("b2", 2, 2, "out, caught at slip")));
            scoreBefore = read(http, "/matches/match-1001/score");
            commentaryBefore = read(http, "/matches/match-1001/commentary");
        }

        try (ConfigurableApplicationContext second = startApp()) {
            RestClient http = client(second);
            JsonNode scoreAfter = read(http, "/matches/match-1001/score");
            JsonNode commentaryAfter = read(http, "/matches/match-1001/commentary");

            assertThat(scoreAfter).isEqualTo(scoreBefore);
            assertThat(commentaryAfter).isEqualTo(commentaryBefore);
            assertThat(scoreAfter.get("status").asText()).isEqualTo("innings_break");
            assertThat(scoreAfter.get("runs").asInt()).isEqualTo(150);
            assertThat(commentaryAfter.get("items")).hasSize(2);
            assertThat(commentaryAfter.get("items").get(1).get("text").asText()).isEqualTo("out, caught at slip");

            // A retry of a delivery processed before the restart is still recognised as a duplicate.
            String retry = post(http, push("r2", snapshot(5, 1, "innings_break", 150, 6, "20.0"), ball("b2", 2, 1, "out")));
            assertThat(json.readTree(retry).get("result").asText()).isEqualTo("duplicate");
        }
    }

    private static ConfigurableApplicationContext startApp() {
        return new SpringApplicationBuilder(AggregatorApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--aggregator.provider-b.enabled=false");
    }

    private static RestClient client(ConfigurableApplicationContext ctx) {
        String port = ctx.getEnvironment().getProperty("local.server.port");
        return RestClient.create("http://localhost:" + port);
    }

    private static String post(RestClient http, String body) {
        return http.post().uri("/webhooks/provider-a").contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(String.class);
    }

    private JsonNode read(RestClient http, String path) throws Exception {
        return json.readTree(http.get().uri(path).retrieve().body(String.class));
    }
}
