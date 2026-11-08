package com.fancode.aggregator;

import com.fancode.aggregator.poll.ProviderBPoller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static com.fancode.aggregator.Events.pollCommentary;
import static com.fancode.aggregator.Events.pollScore;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class PollIngestionTest extends IntegrationTestBase {

    private static final String SCORE_PATH = "/provider/matches/b-match-1001/score";
    private static final String COMMENTARY_PATH = "/provider/matches/b-match-1001/commentary";

    private static final String BALL_1 = pollItem("inn1-12.3", 75, 1, "Defended back to the bowler.");
    private static final String BALL_2 = pollItem("inn1-12.4", 76, 1, "Full and straight, driven to long-on for four.");

    @Autowired
    ProviderBPoller poller;

    @Test
    void repeatedIdenticalPollsDoNotDuplicateOrChangeAnything() throws Exception {
        stubScore(pollScore(15, 1, 98, 2, "12.4", "2026-10-22T14:03:11.000Z"));
        stubCommentary(pollCommentary(BALL_1, BALL_2));

        poller.pollOnce();
        List<Map<String, Object>> snapshotsAfterFirst = jdbc.queryForList("SELECT * FROM provider_snapshots");
        List<Map<String, Object>> commentaryAfterFirst = jdbc.queryForList("SELECT * FROM commentary ORDER BY ball_id");

        poller.pollOnce();
        poller.pollOnce();

        assertThat(count("commentary")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT * FROM provider_snapshots")).isEqualTo(snapshotsAfterFirst);
        assertThat(jdbc.queryForList("SELECT * FROM commentary ORDER BY ball_id")).isEqualTo(commentaryAfterFirst);
        getJson("/matches/" + MATCH + "/score")
                .andExpect(jsonPath("$.runs").value(98))
                .andExpect(jsonPath("$.source").value("provider-b"));
        getJson("/matches/" + MATCH + "/commentary")
                .andExpect(jsonPath("$.items[*].ballId", contains("inn1-12.3", "inn1-12.4")));
    }

    @Test
    void newerPollReplacesScoreAndAddsBall() throws Exception {
        stubScore(pollScore(15, 1, 98, 2, "12.4", "2026-10-22T14:03:11.000Z"));
        stubCommentary(pollCommentary(BALL_1));
        poller.pollOnce();

        stubScore(pollScore(16, 1, 102, 2, "12.5", "2026-10-22T14:03:40.000Z"));
        stubCommentary(pollCommentary(BALL_1, BALL_2));
        poller.pollOnce();

        getJson("/matches/" + MATCH + "/score").andExpect(jsonPath("$.runs").value(102));
        getJson("/matches/" + MATCH + "/commentary").andExpect(jsonPath("$.items", hasSize(2)));
    }

    @Test
    void stalePollWithNewerTimestampLosesToStoredTuple() throws Exception {
        stubScore(pollScore(16, 1, 102, 2, "12.5", "2026-10-22T14:03:40.000Z"));
        stubCommentary(pollCommentary(BALL_1));
        poller.pollOnce();

        // Older sequence, but a later asOf. The tuple decides, not the timestamp.
        stubScore(pollScore(15, 1, 98, 2, "12.4", "2026-10-22T14:09:00.000Z"));
        poller.pollOnce();

        getJson("/matches/" + MATCH + "/score")
                .andExpect(jsonPath("$.runs").value(102))
                .andExpect(jsonPath("$.overs").value("12.5"))
                .andExpect(jsonPath("$.providerSequence").value(16));
    }

    @Test
    void providerErrorLeavesLastGoodStateInPlace() throws Exception {
        stubScore(pollScore(15, 1, 98, 2, "12.4", "2026-10-22T14:03:11.000Z"));
        stubCommentary(pollCommentary(BALL_1, BALL_2));
        poller.pollOnce();

        PROVIDER_B.resetAll();
        PROVIDER_B.stubFor(get(SCORE_PATH).willReturn(aResponse().withStatus(503).withBody("{\"error\":\"unavailable\"}")));
        PROVIDER_B.stubFor(get(COMMENTARY_PATH).willReturn(aResponse().withStatus(503)));
        poller.pollOnce();

        getJson("/matches/" + MATCH + "/score")
                .andExpect(jsonPath("$.runs").value(98))
                .andExpect(jsonPath("$.wickets").value(2));
        getJson("/matches/" + MATCH + "/commentary").andExpect(jsonPath("$.items", hasSize(2)));
        getJson("/matches/" + MATCH + "/providers")
                .andExpect(jsonPath("$.providers[?(@.provider=='provider-b')].health.lastError",
                        contains(containsString("503"))))
                .andExpect(jsonPath("$.providers[?(@.provider=='provider-b')].health.lastSuccessAt",
                        contains(notNullValue())));
    }

    @Test
    void malformedOrInvalidResponseIsTreatedAsProviderError() throws Exception {
        stubScore(pollScore(15, 1, 98, 2, "12.4", "2026-10-22T14:03:11.000Z"));
        stubCommentary(pollCommentary(BALL_1));
        poller.pollOnce();

        stubScore("{\"provider\":\"provider-b\",\"providerMatchId\":\"b-match-1001\",\"snapshot\":{\"sequence\":99}}");
        stubCommentary("<html>gateway</html>");
        poller.pollOnce();

        getJson("/matches/" + MATCH + "/score").andExpect(jsonPath("$.runs").value(98));
        getJson("/matches/" + MATCH + "/commentary").andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void pollCorrectionReplacesBallText() throws Exception {
        stubScore(pollScore(15, 1, 98, 2, "12.4", "2026-10-22T14:03:11.000Z"));
        stubCommentary(pollCommentary(BALL_1, BALL_2));
        poller.pollOnce();

        stubCommentary(pollCommentary(BALL_1, pollItem("inn1-12.4", 76, 2, "Driven to long-on, one bounce, four.")));
        poller.pollOnce();

        getJson("/matches/" + MATCH + "/commentary")
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[1].text").value("Driven to long-on, one bounce, four."));
    }

    private static void stubScore(String body) {
        PROVIDER_B.stubFor(get(SCORE_PATH).willReturn(okJson(body)));
    }

    private static void stubCommentary(String body) {
        PROVIDER_B.stubFor(get(COMMENTARY_PATH).willReturn(okJson(body)));
    }

    private static String pollItem(String ballId, long seq, int rev, String text) {
        return """
                {"ballId":"%s","sequence":%d,"revision":%d,"innings":1,"over":12,"ball":%s,"text":"%s",
                 "occurredAt":"2026-10-22T14:03:11.000Z"}"""
                .formatted(ballId, seq, rev, ballId.substring(ballId.lastIndexOf('.') + 1), text);
    }
}
