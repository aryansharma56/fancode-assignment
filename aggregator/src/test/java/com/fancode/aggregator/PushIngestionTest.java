package com.fancode.aggregator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.fancode.aggregator.Events.ball;
import static com.fancode.aggregator.Events.push;
import static com.fancode.aggregator.Events.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PushIngestionTest extends IntegrationTestBase {

    // The six steps of the worked push example from the assignment.
    private static final String D1 = push("d1", snapshot(1, 1, 4, 0, "0.4"), ball("b1", 1, 1, "four"));
    private static final String D2 = push("d2", snapshot(2, 1, 4, 1, "0.5"), ball("b2", 2, 1, "out"));
    private static final String D4 = push("d4", snapshot(4, 1, 10, 1, "1.1"), ball("b4", 4, 1, "single"));
    private static final String D3 = push("d3", snapshot(3, 1, 8, 1, "1.0"), ball("b3", 3, 1, "six"));
    private static final String D5 = push("d5", snapshot(4, 1, 10, 1, "1.1"), ball("b2", 2, 2, "out, review stays out"));

    @Test
    void orderedIngestion() throws Exception {
        deliver(D1).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("accepted"));
        deliver(D2).andExpect(status().isOk());

        getJson("/matches/" + MATCH + "/score")
                .andExpect(jsonPath("$.runs").value(4))
                .andExpect(jsonPath("$.wickets").value(1))
                .andExpect(jsonPath("$.overs").value("0.5"))
                .andExpect(jsonPath("$.status").value("live"))
                .andExpect(jsonPath("$.source").value("provider-a"));
        getJson("/matches/" + MATCH + "/commentary")
                .andExpect(jsonPath("$.items[*].ballId", contains("b1", "b2")))
                .andExpect(jsonPath("$.items[*].text", contains("four", "out")));
    }

    @Test
    void duplicateDeliveryLeavesStateUnchanged() throws Exception {
        deliver(D1);
        deliver(D2).andExpect(jsonPath("$.result").value("accepted"));
        List<Map<String, Object>> snapshotsBefore = jdbc.queryForList("SELECT * FROM provider_snapshots");
        List<Map<String, Object>> commentaryBefore = jdbc.queryForList("SELECT * FROM commentary ORDER BY ball_id");

        deliver(D2).andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("duplicate"))
                .andExpect(jsonPath("$.snapshotApplied").value(false))
                .andExpect(jsonPath("$.commentaryApplied").value(false));

        assertThat(jdbc.queryForList("SELECT * FROM provider_snapshots")).isEqualTo(snapshotsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM commentary ORDER BY ball_id")).isEqualTo(commentaryBefore);
        assertThat(count("processed_deliveries")).isEqualTo(2);
    }

    @Test
    void olderSnapshotAfterNewerKeepsScoreButStoresLateBallInOrder() throws Exception {
        deliver(D1);
        deliver(D2);
        deliver(D4);
        deliver(D3).andExpect(jsonPath("$.result").value("accepted"))
                .andExpect(jsonPath("$.snapshotApplied").value(false))
                .andExpect(jsonPath("$.commentaryApplied").value(true));

        getJson("/matches/" + MATCH + "/score")
                .andExpect(jsonPath("$.runs").value(10))
                .andExpect(jsonPath("$.wickets").value(1))
                .andExpect(jsonPath("$.overs").value("1.1"))
                .andExpect(jsonPath("$.providerSequence").value(4));
        getJson("/matches/" + MATCH + "/commentary")
                .andExpect(jsonPath("$.items[*].ballId", contains("b1", "b2", "b3", "b4")));
    }

    @Test
    void newerScoreIsServedWhileCommentaryHasAGap() throws Exception {
        deliver(D1);
        deliver(D2);
        deliver(D4);

        getJson("/matches/" + MATCH + "/score").andExpect(jsonPath("$.runs").value(10));
        getJson("/matches/" + MATCH + "/commentary")
                .andExpect(jsonPath("$.items[*].ballId", contains("b1", "b2", "b4")));
    }

    @Test
    void commentaryCorrectionReplacesTextAndKeepsOneBall() throws Exception {
        deliver(D1);
        deliver(D2);
        deliver(D4);
        deliver(D3);
        deliver(D5).andExpect(jsonPath("$.snapshotApplied").value(false))
                .andExpect(jsonPath("$.commentaryApplied").value(true));

        getJson("/matches/" + MATCH + "/score").andExpect(jsonPath("$.runs").value(10));
        getJson("/matches/" + MATCH + "/commentary")
                .andExpect(jsonPath("$.items", hasSize(4)))
                .andExpect(jsonPath("$.items[*].ballId", contains("b1", "b2", "b3", "b4")))
                .andExpect(jsonPath("$.items[1].text").value("out, review stays out"))
                .andExpect(jsonPath("$.items[1].revision").value(2));
    }

    @Test
    void lowerOrEqualCommentaryRevisionDoesNotReplaceText() throws Exception {
        deliver(push("c1", null, ball("b2", 2, 2, "corrected")));
        deliver(push("c2", null, ball("b2", 2, 1, "original, delayed"))).andExpect(jsonPath("$.commentaryApplied").value(false));
        deliver(push("c3", null, ball("b2", 2, 2, "same revision, different text"))).andExpect(jsonPath("$.commentaryApplied").value(false));

        getJson("/matches/" + MATCH + "/commentary")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].text").value("corrected"));
    }

    @Test
    void higherSnapshotRevisionIsAppliedAsScoreCorrection() throws Exception {
        deliver(D4);
        deliver(push("d6", snapshot(4, 2, 10, 2, "1.1"), null))
                .andExpect(jsonPath("$.snapshotApplied").value(true));

        getJson("/matches/" + MATCH + "/score")
                .andExpect(jsonPath("$.wickets").value(2))
                .andExpect(jsonPath("$.providerRevision").value(2));

        // (5,1) beats (4,9); then (4,9) arriving late is ignored.
        deliver(push("d7", snapshot(5, 1, 11, 2, "1.2"), null)).andExpect(jsonPath("$.snapshotApplied").value(true));
        deliver(push("d8", snapshot(4, 9, 99, 9, "9.9"), null)).andExpect(jsonPath("$.snapshotApplied").value(false));
        getJson("/matches/" + MATCH + "/score").andExpect(jsonPath("$.runs").value(11));
    }

    @Test
    void fullWorkedExampleStepByStep() throws Exception {
        deliver(D1);
        expectScore(4, 0);
        expectBalls("b1");

        deliver(D2);
        expectScore(4, 1);
        expectBalls("b1", "b2");

        deliver(D2).andExpect(jsonPath("$.result").value("duplicate"));
        expectScore(4, 1);
        expectBalls("b1", "b2");

        deliver(D4);
        expectScore(10, 1);
        expectBalls("b1", "b2", "b4");

        deliver(D3);
        expectScore(10, 1);
        expectBalls("b1", "b2", "b3", "b4");

        deliver(D5);
        expectScore(10, 1);
        expectBalls("b1", "b2", "b3", "b4");
        getJson("/matches/" + MATCH + "/commentary").andExpect(jsonPath("$.items[1].text").value("out, review stays out"));
    }

    @Test
    void snapshotOnlyStatusChange() throws Exception {
        deliver(D4);
        deliver(push("s1", snapshot(5, 1, "innings_break", 10, 1, "1.1"), null))
                .andExpect(jsonPath("$.commentaryApplied").value(false));
        getJson("/matches/" + MATCH).andExpect(jsonPath("$.status").value("innings_break"));
    }

    @Test
    void rejectsInvalidPayloadsWithoutStoringThem() throws Exception {
        deliver("{not json").andExpect(status().isBadRequest());
        deliver(push("x1", null, null)).andExpect(status().isBadRequest());
        deliver(push("x2", snapshot(1, 0, 4, 0, "0.4"), null)).andExpect(status().isBadRequest());
        deliver(push("x3", snapshot(1, 1, "playing", 4, 0, "0.4"), null)).andExpect(status().isBadRequest());
        deliver(D1.replace("a-match-1001", "a-match-9999")).andExpect(status().isUnprocessableEntity());

        assertThat(count("processed_deliveries")).isZero();
        assertThat(count("provider_snapshots")).isZero();
    }

    private void expectScore(int runs, int wickets) throws Exception {
        getJson("/matches/" + MATCH + "/score")
                .andExpect(jsonPath("$.runs").value(runs))
                .andExpect(jsonPath("$.wickets").value(wickets));
    }

    private void expectBalls(String... ballIds) throws Exception {
        getJson("/matches/" + MATCH + "/commentary").andExpect(jsonPath("$.items[*].ballId", contains(ballIds)));
    }
}
