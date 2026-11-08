package com.fancode.mockpush;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks that each bundled scenario contains every delivery pattern the assignment requires. */
class ScenarioTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"worked-example", "match-timeline"})
    void scenarioCoversRequiredDeliveryPatterns(String name) throws Exception {
        List<JsonNode> bodies = Scenario.load(name, mapper).deliveries().stream().map(Scenario.Delivery::body).toList();

        boolean repeatedDeliveryId = false;
        boolean outOfOrderCommentary = false;
        boolean olderSnapshotAfterNewer = false;
        boolean correction = false;

        Set<String> seenDeliveries = new HashSet<>();
        Set<String> seenBalls = new HashSet<>();
        long maxBallSeq = Long.MIN_VALUE;
        long maxSnapshotSeq = Long.MIN_VALUE;

        for (JsonNode body : bodies) {
            assertThat(body.path("provider").asText()).isEqualTo("provider-a");
            assertThat(body.path("providerMatchId").asText()).isEqualTo("a-match-1001");

            if (!seenDeliveries.add(body.path("deliveryId").asText())) {
                repeatedDeliveryId = true;
                continue;
            }
            JsonNode snap = body.get("snapshot");
            if (snap != null) {
                long seq = snap.get("sequence").asLong();
                olderSnapshotAfterNewer |= seq < maxSnapshotSeq;
                maxSnapshotSeq = Math.max(maxSnapshotSeq, seq);
            }
            JsonNode ball = body.get("commentary");
            if (ball != null) {
                long seq = ball.get("sequence").asLong();
                outOfOrderCommentary |= seq < maxBallSeq;
                maxBallSeq = Math.max(maxBallSeq, seq);
                correction |= !seenBalls.add(ball.get("ballId").asText()) && ball.get("revision").asInt() > 1;
            }
        }

        assertThat(repeatedDeliveryId).as("repeated deliveryId").isTrue();
        assertThat(outOfOrderCommentary).as("higher commentary sequence before lower").isTrue();
        assertThat(olderSnapshotAfterNewer).as("delayed older snapshot").isTrue();
        assertThat(correction).as("correction of an earlier ball").isTrue();
    }
}
