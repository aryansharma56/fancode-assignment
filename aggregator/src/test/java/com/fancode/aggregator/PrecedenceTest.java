package com.fancode.aggregator;

import com.fancode.aggregator.ingest.BallData;
import com.fancode.aggregator.ingest.IngestionService;
import com.fancode.aggregator.ingest.SnapshotData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static com.fancode.aggregator.Events.ball;
import static com.fancode.aggregator.Events.push;
import static com.fancode.aggregator.Events.snapshot;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Primary provider is provider-a (see IntegrationTestBase). */
class PrecedenceTest extends IntegrationTestBase {

    @Autowired
    IngestionService ingestion;

    @Test
    void beforeAnyDataMatchIsScheduledWithEmptyCommentary() throws Exception {
        getJson("/matches")
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(MATCH))
                .andExpect(jsonPath("$[0].status").value("scheduled"))
                .andExpect(jsonPath("$[0].score").doesNotExist());
        getJson("/matches/" + MATCH + "/score").andExpect(jsonPath("$.status").value("scheduled"));
        getJson("/matches/" + MATCH + "/commentary").andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void unknownMatchIsNotFound() throws Exception {
        getJson("/matches/nope").andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("match_not_found"));
        getJson("/matches/nope/score").andExpect(status().isNotFound());
        getJson("/matches/nope/commentary").andExpect(status().isNotFound());
    }

    @Test
    void fallsBackToSecondaryWhenPrimaryHasNothing() throws Exception {
        ingestB(50, 120, 3, "15.0", "B ball");

        getJson("/matches/" + MATCH + "/score")
                .andExpect(jsonPath("$.runs").value(120))
                .andExpect(jsonPath("$.source").value("provider-b"));
        getJson("/matches/" + MATCH + "/commentary")
                .andExpect(jsonPath("$.source").value("provider-b"))
                .andExpect(jsonPath("$.items[*].text", contains("B ball")));
    }

    @Test
    void primaryWinsWhenBothProvidersHaveData() throws Exception {
        // B has a much higher sequence number; that must not matter across providers.
        ingestB(500, 120, 3, "15.0", "B ball");
        deliver(push("d1", snapshot(3, 1, 98, 2, "12.4"), ball("a-ball", 3, 1, "A ball")));

        getJson("/matches/" + MATCH + "/score")
                .andExpect(jsonPath("$.runs").value(98))
                .andExpect(jsonPath("$.source").value("provider-a"));
        getJson("/matches/" + MATCH + "/commentary")
                .andExpect(jsonPath("$.source").value("provider-a"))
                .andExpect(jsonPath("$.items[*].text", contains("A ball")));
        getJson("/matches")
                .andExpect(jsonPath("$[0].score").value("98/2 (12.4 ov)"))
                .andExpect(jsonPath("$[0].source").value("provider-a"));
        getJson("/matches/" + MATCH + "/providers")
                .andExpect(jsonPath("$.primaryProvider").value("provider-a"))
                .andExpect(jsonPath("$.providers[*].provider", contains("provider-a", "provider-b")))
                .andExpect(jsonPath("$.providers[1].snapshot.sequence").value(500));
    }

    private void ingestB(long seq, int runs, int wickets, String overs, String text) {
        ingestion.ingestSnapshot("provider-b", "b-match-1001",
                new SnapshotData(seq, 1, "live", 1, runs, wickets, overs, null));
        ingestion.ingestCommentary("provider-b", "b-match-1001",
                List.of(new BallData("b-ball", seq, 1, 1, 14, 6, text, null)));
    }
}
