package com.fancode.aggregator.poll;

import com.fancode.aggregator.ingest.BallData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.List;

public record ProviderBCommentaryResponse(
        String provider,
        @NotBlank String providerMatchId,
        @NotNull List<@NotNull @Valid Item> items) {

    public record Item(
            @NotBlank String ballId,
            @NotNull @PositiveOrZero Long sequence,
            @NotNull @Min(1) Integer revision,
            Integer innings,
            Integer over,
            Integer ball,
            @NotNull String text,
            Instant occurredAt) {

        public BallData toBallData() {
            return new BallData(ballId, sequence, revision, innings, over, ball, text, occurredAt);
        }
    }
}
