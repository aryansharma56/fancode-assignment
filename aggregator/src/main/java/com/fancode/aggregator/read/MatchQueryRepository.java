package com.fancode.aggregator.read;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Read-side SQL. Plain row records; precedence is applied in {@link MatchViewService}. */
@Repository
public class MatchQueryRepository {

    public record MatchRow(String id, String title) {
    }

    public record ProviderLink(String provider, String providerMatchId) {
    }

    public record SnapshotRow(String provider, long sequence, int revision, String status, int innings,
                              int runs, int wickets, String overs, Instant sourceTime, Instant receivedAt) {
    }

    public record BallRow(String ballId, long sequence, int revision, Integer innings, Integer over,
                          Integer ball, String text, Instant occurredAt, Instant updatedAt) {
    }

    public record HealthRow(Instant lastSuccessAt, Instant lastErrorAt, String lastError) {
    }

    private final JdbcTemplate jdbc;

    public MatchQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MatchRow> findMatches() {
        return jdbc.query("SELECT id, title FROM matches ORDER BY id",
                (rs, i) -> new MatchRow(rs.getString("id"), rs.getString("title")));
    }

    public Optional<MatchRow> findMatch(String matchId) {
        return jdbc.query("SELECT id, title FROM matches WHERE id = ?",
                (rs, i) -> new MatchRow(rs.getString("id"), rs.getString("title")), matchId).stream().findFirst();
    }

    public List<ProviderLink> providerLinks(String matchId) {
        return jdbc.query(
                "SELECT provider, provider_match_id FROM provider_match_map WHERE match_id = ? ORDER BY provider",
                (rs, i) -> new ProviderLink(rs.getString("provider"), rs.getString("provider_match_id")), matchId);
    }

    public Optional<SnapshotRow> snapshot(String matchId, String provider) {
        return jdbc.query("""
                SELECT provider, sequence, revision, status, innings, runs, wickets, overs, source_time, received_at
                FROM provider_snapshots WHERE match_id = ? AND provider = ?
                """, (rs, i) -> new SnapshotRow(
                        rs.getString("provider"), rs.getLong("sequence"), rs.getInt("revision"),
                        rs.getString("status"), rs.getInt("innings"), rs.getInt("runs"), rs.getInt("wickets"),
                        rs.getString("overs"), instant(rs, "source_time"), instant(rs, "received_at")),
                matchId, provider).stream().findFirst();
    }

    /** One row per logical ball, in provider display order. ball_id breaks sequence ties deterministically. */
    public List<BallRow> commentary(String matchId, String provider) {
        return jdbc.query("""
                SELECT ball_id, sequence, revision, innings, over_no, ball_no, text, occurred_at, updated_at
                FROM commentary WHERE match_id = ? AND provider = ?
                ORDER BY sequence, ball_id
                """, (rs, i) -> new BallRow(
                        rs.getString("ball_id"), rs.getLong("sequence"), rs.getInt("revision"),
                        (Integer) rs.getObject("innings"), (Integer) rs.getObject("over_no"),
                        (Integer) rs.getObject("ball_no"), rs.getString("text"),
                        instant(rs, "occurred_at"), instant(rs, "updated_at")),
                matchId, provider);
    }

    public boolean hasCommentary(String matchId, String provider) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM commentary WHERE match_id = ? AND provider = ?)",
                Boolean.class, matchId, provider);
        return Boolean.TRUE.equals(exists);
    }

    public int commentaryCount(String matchId, String provider) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM commentary WHERE match_id = ? AND provider = ?",
                Integer.class, matchId, provider);
        return count == null ? 0 : count;
    }

    public Optional<HealthRow> health(String matchId, String provider) {
        return jdbc.query(
                "SELECT last_success_at, last_error_at, last_error FROM provider_health WHERE match_id = ? AND provider = ?",
                (rs, i) -> new HealthRow(instant(rs, "last_success_at"), instant(rs, "last_error_at"),
                        rs.getString("last_error")),
                matchId, provider).stream().findFirst();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
