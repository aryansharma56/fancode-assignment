package com.fancode.mockpoll;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MockPollProviderTest {

    private static final String SCORE = "/provider/matches/b-match-1001/score";
    private static final String COMMENTARY = "/provider/matches/b-match-1001/commentary";

    @Autowired
    MockMvc mvc;

    @Autowired
    Timeline timeline;

    @BeforeEach
    void reset() {
        timeline.jumpTo(1);
    }

    @Test
    void scriptCoversRequiredScenarios() {
        var steps = timeline.steps();
        assertThat(steps.get(1).score()).isEqualTo(steps.get(0).score());               // unchanged
        assertThat(steps.get(1).commentary()).isEqualTo(steps.get(0).commentary());
        assertThat(steps.get(2).score().get("sequence").asLong())
                .isGreaterThan(steps.get(1).score().get("sequence").asLong());           // newer score
        assertThat(steps.get(2).commentary()).hasSizeGreaterThan(steps.get(1).commentary().size()); // new ball
        assertThat(steps.get(3).scoreError()).isEqualTo(503);                            // temporary error
        assertThat(steps.get(4).score().get("sequence").asLong())
                .isLessThan(steps.get(2).score().get("sequence").asLong());              // stale
        assertThat(steps.get(4).score().get("asOf").asText())
                .isGreaterThan(steps.get(2).score().get("asOf").asText());               // ...with a newer asOf
    }

    @Test
    void servesCurrentStepAndAdvancesDeterministically() throws Exception {
        mvc.perform(get(SCORE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("provider-b"))
                .andExpect(jsonPath("$.snapshot.sequence").value(10));
        mvc.perform(get(COMMENTARY)).andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].key").doesNotExist());

        String first = mvc.perform(get(COMMENTARY)).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/provider/admin/step")).andExpect(jsonPath("$.currentStep").value(2));
        mvc.perform(get(COMMENTARY)).andExpect(content().json(first, true));

        mvc.perform(put("/provider/admin/step/4"));
        mvc.perform(get(SCORE)).andExpect(status().isServiceUnavailable());
        mvc.perform(get(COMMENTARY)).andExpect(status().isServiceUnavailable());
    }

    @Test
    void staysOnLastStepAndRejectsBadJumps() throws Exception {
        for (int i = 0; i < timeline.stepCount() + 3; i++) {
            timeline.advance();
        }
        assertThat(timeline.currentStepNumber()).isEqualTo(timeline.stepCount());
        mvc.perform(put("/provider/admin/step/0")).andExpect(status().isBadRequest());
        mvc.perform(get("/provider/matches/other/score")).andExpect(status().isNotFound());
    }
}
