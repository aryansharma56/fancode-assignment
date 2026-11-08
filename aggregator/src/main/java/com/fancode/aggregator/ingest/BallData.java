package com.fancode.aggregator.ingest;

import java.time.Instant;

/** Internal, provider-neutral commentary item for one logical ball. */
public record BallData(
        String ballId,
        long sequence,
        int revision,
        Integer innings,
        Integer over,
        Integer ball,
        String text,
        Instant occurredAt) {
}
