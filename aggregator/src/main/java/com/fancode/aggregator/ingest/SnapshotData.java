package com.fancode.aggregator.ingest;

import java.time.Instant;

/**
 * Internal, provider-neutral score snapshot. Both the push and the poll path map into this.
 *
 * @param sourceTime the provider's occurredAt / asOf. Stored for display and lag analysis only;
 *                   it never decides which snapshot wins.
 */
public record SnapshotData(
        long sequence,
        int revision,
        String status,
        int innings,
        int runs,
        int wickets,
        String overs,
        Instant sourceTime) {
}
