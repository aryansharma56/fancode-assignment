package com.fancode.aggregator.push;

import com.fancode.aggregator.ingest.BallData;
import com.fancode.aggregator.ingest.Providers;
import com.fancode.aggregator.ingest.SnapshotData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/** Webhook body sent by Provider A. snapshot and commentary are each optional. */
public record ProviderAEvent(
        @NotBlank String provider,
        @NotBlank String deliveryId,
        Instant occurredAt,
        @NotBlank String providerMatchId,
        @Valid Snapshot snapshot,
        @Valid Commentary commentary) {

    public record Snapshot(
            @NotNull @PositiveOrZero Long sequence,
            @NotNull @Min(1) Integer revision,
            @NotNull @Pattern(regexp = Providers.STATUS_PATTERN) String status,
            @NotNull @Min(1) Integer innings,
            @NotNull @PositiveOrZero Integer runs,
            @NotNull @PositiveOrZero Integer wickets,
            @NotBlank String overs) {
    }

    public record Commentary(
            @NotBlank String ballId,
            @NotNull @PositiveOrZero Long sequence,
            @NotNull @Min(1) Integer revision,
            Integer innings,
            Integer over,
            Integer ball,
            @NotNull String text) {
    }

    public SnapshotData toSnapshotData() {
        if (snapshot == null) {
            return null;
        }
        return new SnapshotData(snapshot.sequence, snapshot.revision, snapshot.status, snapshot.innings,
                snapshot.runs, snapshot.wickets, snapshot.overs, occurredAt);
    }

    public BallData toBallData() {
        if (commentary == null) {
            return null;
        }
        return new BallData(commentary.ballId, commentary.sequence, commentary.revision, commentary.innings,
                commentary.over, commentary.ball, commentary.text, occurredAt);
    }
}
