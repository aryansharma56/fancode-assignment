package com.fancode.aggregator.poll;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Drives the poller on a fixed delay (the next poll starts after the previous one finishes,
 * so polls never overlap). Disabled in tests, which call {@link ProviderBPoller#pollOnce()}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "aggregator.provider-b", name = "enabled", havingValue = "true")
public class ProviderBPollScheduler {

    private final ProviderBPoller poller;

    public ProviderBPollScheduler(ProviderBPoller poller) {
        this.poller = poller;
    }

    @Scheduled(fixedDelayString = "${aggregator.provider-b.poll-interval-ms}", initialDelay = 1000)
    public void poll() {
        poller.pollOnce();
    }
}
