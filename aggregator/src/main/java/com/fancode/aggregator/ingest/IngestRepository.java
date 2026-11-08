package com.fancode.aggregator.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * All write-side SQL. The ordering and revision rules live in the WHERE clauses of the
 * conditional upserts below, so each rule is enforced by a single atomic statement.
 */
@Repository
public class IngestRepository {

    private final JdbcTemplate jdbc;

    public IngestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> resolveMatchId(String provider, String providerMatchId) {
        List<String> ids = jdbc.queryForList(
                "SELECT match_id FROM provider_match_map WHERE provider = ? AND provider_match_id = ?",
                String.class, provider, providerMatchId);
        return ids.stream().findFirst();
    }

    /**
     * Records a push delivery. Returns true if this (provider, deliveryId) is new.
     * If another transaction is inserting the same key concurrently, Postgres makes this
     * statement wait for it, so exactly one caller ever sees true.
     */
    public boolean recordDelivery(String provider, String deliveryId, String payloadHash) {
        int rows = jdbc.update("""
                INSERT INTO processed_deliveries (provider, delivery_id, payload_hash)
                VALUES (?, ?, ?)
                ON CONFLICT (provider, delivery_id) DO NOTHING
                """, provider, deliveryId, payloadHash);
        return rows == 1;
    }

    public Optional<String> deliveryHash(String provider, String deliveryId) {
        List<String> hashes = jdbc.queryForList(
                "SELECT payload_hash FROM processed_deliveries WHERE provider = ? AND delivery_id = ?",
                String.class, provider, deliveryId);
        return hashes.stream().findFirst();
    }

    /**
     * Stores the snapshot only if (sequence, revision) is strictly newer than the stored tuple
     * for this match and provider. Returns true if it was applied.
     *
     * <p>Postgres compares row values lexicographically, which is exactly the rule:
     * (5,1) &gt; (4,9), (4,2) &gt; (4,1), and (4,1) is not &gt; (4,1).
     */
    public boolean applySnapshot(String matchId, String provider, SnapshotData s) {
        int rows = jdbc.update("""
                INSERT INTO provider_snapshots
                    (match_id, provider, sequence, revision, status, innings, runs, wickets, overs,
                     source_time, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (match_id, provider) DO UPDATE SET
                    sequence    = EXCLUDED.sequence,
                    revision    = EXCLUDED.revision,
                    status      = EXCLUDED.status,
                    innings     = EXCLUDED.innings,
                    runs        = EXCLUDED.runs,
                    wickets     = EXCLUDED.wickets,
                    overs       = EXCLUDED.overs,
                    source_time = EXCLUDED.source_time,
                    received_at = EXCLUDED.received_at
                WHERE (provider_snapshots.sequence, provider_snapshots.revision)
                    < (EXCLUDED.sequence, EXCLUDED.revision)
                """,
                matchId, provider, s.sequence(), s.revision(), s.status(), s.innings(),
                s.runs(), s.wickets(), s.overs(), toOffset(s.sourceTime()));
        return rows == 1;
    }

    /**
     * Stores a ball, or replaces the stored ball only when the incoming revision is higher.
     * An equal or lower revision is a no-op, so re-polling an unchanged list writes nothing.
     */
    public boolean applyBall(String matchId, String provider, BallData b) {
        int rows = jdbc.update("""
                INSERT INTO commentary
                    (match_id, provider, ball_id, sequence, revision, innings, over_no, ball_no,
                     text, occurred_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (match_id, provider, ball_id) DO UPDATE SET
                    sequence    = EXCLUDED.sequence,
                    revision    = EXCLUDED.revision,
                    innings     = EXCLUDED.innings,
                    over_no     = EXCLUDED.over_no,
                    ball_no     = EXCLUDED.ball_no,
                    text        = EXCLUDED.text,
                    occurred_at = EXCLUDED.occurred_at,
                    updated_at  = EXCLUDED.updated_at
                WHERE commentary.revision < EXCLUDED.revision
                """,
                matchId, provider, b.ballId(), b.sequence(), b.revision(), b.innings(),
                b.over(), b.ball(), b.text(), toOffset(b.occurredAt()));
        return rows == 1;
    }

    public void recordSuccess(String matchId, String provider) {
        jdbc.update("""
                INSERT INTO provider_health (match_id, provider, last_success_at)
                VALUES (?, ?, now())
                ON CONFLICT (match_id, provider) DO UPDATE SET last_success_at = EXCLUDED.last_success_at
                """, matchId, provider);
    }

    public void recordError(String matchId, String provider, String error) {
        jdbc.update("""
                INSERT INTO provider_health (match_id, provider, last_error_at, last_error)
                VALUES (?, ?, now(), ?)
                ON CONFLICT (match_id, provider) DO UPDATE SET
                    last_error_at = EXCLUDED.last_error_at,
                    last_error    = EXCLUDED.last_error
                """, matchId, provider, error);
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
