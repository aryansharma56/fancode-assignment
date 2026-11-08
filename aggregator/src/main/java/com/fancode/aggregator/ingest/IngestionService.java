package com.fancode.aggregator.ingest;

import com.fancode.aggregator.web.UnknownProviderMatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Single entry point for writes from both providers. Push and poll share the same snapshot
 * and revision rules; push additionally deduplicates by deliveryId.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    public enum PushOutcome { ACCEPTED, DUPLICATE }

    public record PushResult(PushOutcome outcome, String matchId, boolean snapshotApplied,
                             boolean commentaryApplied) {
    }

    private final IngestRepository repo;

    public IngestionService(IngestRepository repo) {
        this.repo = repo;
    }

    /**
     * Applies one push delivery atomically. The delivery marker and its effects commit in the
     * same transaction: either both are stored or neither is, so a retry after a crash is safe.
     *
     * @param snapshot may be null
     * @param ball     may be null
     */
    @Transactional
    public PushResult ingestPush(String provider, String deliveryId, String payloadHash,
                                 String providerMatchId, SnapshotData snapshot, BallData ball) {
        String matchId = resolve(provider, providerMatchId);

        if (!repo.recordDelivery(provider, deliveryId, payloadHash)) {
            repo.deliveryHash(provider, deliveryId)
                    .filter(stored -> !stored.equals(payloadHash))
                    .ifPresent(stored -> log.warn(
                            "Delivery {}/{} repeated with a different payload; ignoring the repeat",
                            provider, deliveryId));
            return new PushResult(PushOutcome.DUPLICATE, matchId, false, false);
        }

        boolean snapshotApplied = snapshot != null && repo.applySnapshot(matchId, provider, snapshot);
        boolean commentaryApplied = ball != null && repo.applyBall(matchId, provider, ball);
        return new PushResult(PushOutcome.ACCEPTED, matchId, snapshotApplied, commentaryApplied);
    }

    /** Poll path: applies a snapshot if strictly newer. Returns true if applied. */
    @Transactional
    public boolean ingestSnapshot(String provider, String providerMatchId, SnapshotData snapshot) {
        return repo.applySnapshot(resolve(provider, providerMatchId), provider, snapshot);
    }

    /** Poll path: applies each ball by revision rule. Returns how many rows changed. */
    @Transactional
    public int ingestCommentary(String provider, String providerMatchId, List<BallData> balls) {
        String matchId = resolve(provider, providerMatchId);
        int changed = 0;
        for (BallData ball : balls) {
            if (repo.applyBall(matchId, provider, ball)) {
                changed++;
            }
        }
        return changed;
    }

    private String resolve(String provider, String providerMatchId) {
        return repo.resolveMatchId(provider, providerMatchId)
                .orElseThrow(() -> new UnknownProviderMatchException(provider, providerMatchId));
    }
}
