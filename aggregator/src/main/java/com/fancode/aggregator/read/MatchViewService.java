package com.fancode.aggregator.read;

import com.fancode.aggregator.config.AggregatorProperties;
import com.fancode.aggregator.read.MatchQueryRepository.BallRow;
import com.fancode.aggregator.read.MatchQueryRepository.MatchRow;
import com.fancode.aggregator.read.MatchQueryRepository.ProviderLink;
import com.fancode.aggregator.read.MatchQueryRepository.SnapshotRow;
import com.fancode.aggregator.read.MatchViews.CommentaryItem;
import com.fancode.aggregator.read.MatchViews.CommentaryView;
import com.fancode.aggregator.read.MatchViews.Health;
import com.fancode.aggregator.read.MatchViews.MatchDetail;
import com.fancode.aggregator.read.MatchViews.MatchSummary;
import com.fancode.aggregator.read.MatchViews.ProviderSnapshot;
import com.fancode.aggregator.read.MatchViews.ProviderState;
import com.fancode.aggregator.read.MatchViews.ProvidersView;
import com.fancode.aggregator.read.MatchViews.ScoreView;
import com.fancode.aggregator.web.MatchNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Builds the single client view from per-provider stored state.
 *
 * <p>Precedence rule: providers are ranked with the configured primary first, then the rest by
 * name. The score comes from the highest-ranked provider that has a stored snapshot; the
 * commentary comes from the highest-ranked provider that has at least one stored ball. Data from
 * different providers is never merged and their sequence numbers are never compared.
 */
@Service
public class MatchViewService {

    private static final String DEFAULT_STATUS = "scheduled";

    private final MatchQueryRepository repo;
    private final String primaryProvider;

    public MatchViewService(MatchQueryRepository repo, AggregatorProperties props) {
        this.repo = repo;
        this.primaryProvider = props.primaryProvider();
    }

    public List<MatchSummary> listMatches() {
        return repo.findMatches().stream().map(match -> {
            Optional<SnapshotRow> snap = currentSnapshot(match.id());
            return new MatchSummary(match.id(), match.title(),
                    snap.map(SnapshotRow::status).orElse(DEFAULT_STATUS),
                    snap.map(MatchViewService::summary).orElse(null),
                    snap.map(SnapshotRow::provider).orElse(null));
        }).toList();
    }

    public MatchDetail match(String matchId) {
        MatchRow match = requireMatch(matchId);
        ScoreView score = score(matchId);
        return new MatchDetail(match.id(), match.title(), score.status(), score.runs() == null ? null : score);
    }

    public ScoreView score(String matchId) {
        requireMatch(matchId);
        return currentSnapshot(matchId)
                .map(s -> new ScoreView(matchId, s.status(), s.innings(), s.runs(), s.wickets(), s.overs(),
                        summary(s), s.provider(), s.sequence(), s.revision(), s.sourceTime(), s.receivedAt()))
                .orElse(new ScoreView(matchId, DEFAULT_STATUS, null, null, null, null, null,
                        null, null, null, null, null));
    }

    public CommentaryView commentary(String matchId) {
        requireMatch(matchId);
        for (String provider : rankedProviders(matchId)) {
            if (repo.hasCommentary(matchId, provider)) {
                List<CommentaryItem> items = repo.commentary(matchId, provider).stream()
                        .map(MatchViewService::toItem).toList();
                return new CommentaryView(matchId, provider, items);
            }
        }
        return new CommentaryView(matchId, null, List.of());
    }

    public ProvidersView providers(String matchId) {
        requireMatch(matchId);
        List<ProviderState> states = repo.providerLinks(matchId).stream()
                .sorted(rankComparator())
                .map(link -> new ProviderState(link.provider(), link.providerMatchId(),
                        repo.snapshot(matchId, link.provider()).map(s -> new ProviderSnapshot(
                                s.sequence(), s.revision(), s.status(), s.innings(), s.runs(), s.wickets(),
                                s.overs(), s.sourceTime(), s.receivedAt())).orElse(null),
                        repo.commentaryCount(matchId, link.provider()),
                        repo.health(matchId, link.provider())
                                .map(h -> new Health(h.lastSuccessAt(), h.lastErrorAt(), h.lastError()))
                                .orElse(null)))
                .toList();
        return new ProvidersView(matchId, primaryProvider, states);
    }

    private Optional<SnapshotRow> currentSnapshot(String matchId) {
        for (String provider : rankedProviders(matchId)) {
            Optional<SnapshotRow> snap = repo.snapshot(matchId, provider);
            if (snap.isPresent()) {
                return snap;
            }
        }
        return Optional.empty();
    }

    /** Primary provider first, then the others alphabetically. Deterministic. */
    List<String> rankedProviders(String matchId) {
        return repo.providerLinks(matchId).stream()
                .sorted(rankComparator())
                .map(ProviderLink::provider)
                .toList();
    }

    private Comparator<ProviderLink> rankComparator() {
        return Comparator.comparing((ProviderLink l) -> !l.provider().equals(primaryProvider))
                .thenComparing(ProviderLink::provider);
    }

    private MatchRow requireMatch(String matchId) {
        return repo.findMatch(matchId).orElseThrow(() -> new MatchNotFoundException(matchId));
    }

    private static String summary(SnapshotRow s) {
        return s.runs() + "/" + s.wickets() + " (" + s.overs() + " ov)";
    }

    private static CommentaryItem toItem(BallRow b) {
        return new CommentaryItem(b.ballId(), b.sequence(), b.revision(), b.innings(), b.over(), b.ball(),
                b.text(), b.occurredAt(), b.updatedAt());
    }
}
