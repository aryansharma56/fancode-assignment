package com.fancode.mockpoll;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Optional demo mode: walks through the timeline on a timer. Off unless mock.auto-advance-ms > 0. */
@Component
@ConditionalOnExpression("${mock.auto-advance-ms:0} > 0")
public class AutoAdvance {

    private static final Logger log = LoggerFactory.getLogger(AutoAdvance.class);

    private final Timeline timeline;

    public AutoAdvance(Timeline timeline) {
        this.timeline = timeline;
    }

    @Scheduled(fixedDelayString = "${mock.auto-advance-ms}", initialDelayString = "${mock.auto-advance-ms}")
    public void tick() {
        if (timeline.currentStepNumber() < timeline.stepCount()) {
            timeline.advance();
            log.info("Advanced to step {}: {}", timeline.currentStepNumber(), timeline.currentStep().name());
        }
    }
}
