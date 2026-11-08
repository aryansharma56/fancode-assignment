package com.fancode.aggregator.read;

import java.time.Instant;
import java.util.List;

/** Client-facing response shapes. Null fields are omitted from JSON. */
public final class MatchViews {

    private MatchViews() {
    }

    public record MatchSummary(String id, String title, String status, String score, String source) {
    }

    public record MatchDetail(String id, String title, String status, ScoreView score) {
    }

    /**
     * @param source           provider whose snapshot is shown
     * @param providerSequence the source provider's sequence; only meaningful within that provider
     * @param providerTime     provider's occurredAt / asOf for this snapshot
     * @param updatedAt        when the aggregator applied this snapshot
     */
    public record ScoreView(String matchId, String status, Integer innings, Integer runs, Integer wickets,
                            String overs, String summary, String source, Long providerSequence,
                            Integer providerRevision, Instant providerTime, Instant updatedAt) {
    }

    public record CommentaryView(String matchId, String source, List<CommentaryItem> items) {
    }

    public record CommentaryItem(String ballId, long sequence, int revision, Integer innings, Integer over,
                                 Integer ball, String text, Instant occurredAt, Instant updatedAt) {
    }

    public record ProvidersView(String matchId, String primaryProvider, List<ProviderState> providers) {
    }

    public record ProviderState(String provider, String providerMatchId, ProviderSnapshot snapshot,
                                int commentaryCount, Health health) {
    }

    public record ProviderSnapshot(long sequence, int revision, String status, int innings, int runs,
                                   int wickets, String overs, Instant providerTime, Instant receivedAt) {
    }

    public record Health(Instant lastSuccessAt, Instant lastErrorAt, String lastError) {
    }
}
