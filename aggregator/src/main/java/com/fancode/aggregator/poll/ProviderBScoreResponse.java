package com.fancode.aggregator.poll;

import com.fancode.aggregator.ingest.Providers;
import com.fancode.aggregator.ingest.SnapshotData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

public record ProviderBScoreResponse(
        String provider,
        @NotBlank String providerMatchId,
        @NotNull @Valid Snapshot snapshot) {

    public record Snapshot(
            @NotNull @PositiveOrZero Long sequence,
            @NotNull @Min(1) Integer revision,
            @NotNull @Pattern(regexp = Providers.STATUS_PATTERN) String status,
            @NotNull @Min(1) Integer innings,
            @NotNull @PositiveOrZero Integer runs,
            @NotNull @PositiveOrZero Integer wickets,
            @NotBlank String overs,
            Instant asOf) {

        public SnapshotData toSnapshotData() {
            return new SnapshotData(sequence, revision, status, innings, runs, wickets, overs, asOf);
        }
    }
}
