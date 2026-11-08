package com.fancode.mockpush;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sends one scenario to the aggregator webhook, then exits.
 *
 * <p>Follows the webhook contract: a 2xx is final, a 4xx is logged and not retried, and a 5xx or a
 * connection failure is retried with the same body (and therefore the same deliveryId).
 */
@Component
public class PushRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(PushRunner.class);

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Value("${target-url}")
    private String targetUrl;
    @Value("${scenario}")
    private String scenarioName;
    @Value("${delay-ms}")
    private long delayMs;
    @Value("${max-attempts}")
    private int maxAttempts;

    private int failures;

    public PushRunner(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void run(String... args) throws Exception {
        Scenario scenario = Scenario.load(scenarioName, mapper);
        log.info("Scenario '{}': {} ({} deliveries) -> {}", scenario.name(), scenario.description(),
                scenario.deliveries().size(), targetUrl);

        int n = 0;
        for (Scenario.Delivery delivery : scenario.deliveries()) {
            n++;
            String body = mapper.writeValueAsString(delivery.body());
            log.info("[{}/{}] {} (deliveryId={})", n, scenario.deliveries().size(), delivery.note(),
                    delivery.body().path("deliveryId").asText());
            send(body);
            Thread.sleep(delayMs);
        }
        log.info("Done. {} deliveries without a 2xx.", failures);
    }

    private void send(String body) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status < 300) {
                    log.info("    -> {} {}", status, response.body());
                    return;
                }
                if (status < 500) {
                    log.warn("    -> {} {} (rejected; not retrying)", status, response.body());
                    failures++;
                    return;
                }
                log.warn("    -> {} on attempt {}; retrying with the same deliveryId", status, attempt);
            } catch (IOException e) {
                log.warn("    -> {} on attempt {}; retrying with the same deliveryId", e.getClass().getSimpleName(), attempt);
            }
            Thread.sleep(500L * attempt);
        }
        log.error("    -> gave up after {} attempts", maxAttempts);
        failures++;
    }

    @Override
    public int getExitCode() {
        return failures == 0 ? 0 : 1;
    }
}
