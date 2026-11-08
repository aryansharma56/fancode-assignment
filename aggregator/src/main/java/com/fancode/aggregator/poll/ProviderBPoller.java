package com.fancode.aggregator.poll;

import com.fancode.aggregator.config.AggregatorProperties;
import com.fancode.aggregator.ingest.IngestRepository;
import com.fancode.aggregator.ingest.IngestionService;
import com.fancode.aggregator.ingest.Providers;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Polls Provider B once per configured match. Score and commentary are fetched and applied
 * independently, so a failure of one does not block the other.
 *
 * <p>Any failure (HTTP error such as 503, timeout, unparseable or invalid body) is recorded in
 * provider_health and nothing else is written. The last good state stays in place.
 */
@Component
public class ProviderBPoller {

    private static final Logger log = LoggerFactory.getLogger(ProviderBPoller.class);
    private static final String PROVIDER = Providers.PROVIDER_B;

    private final ProviderBClient client;
    private final IngestionService ingestion;
    private final IngestRepository repo;
    private final Validator validator;
    private final AggregatorProperties props;

    public ProviderBPoller(ProviderBClient client, IngestionService ingestion, IngestRepository repo,
                           Validator validator, AggregatorProperties props) {
        this.client = client;
        this.ingestion = ingestion;
        this.repo = repo;
        this.validator = validator;
        this.props = props;
    }

    public void pollOnce() {
        for (String providerMatchId : props.providerB().matchIds()) {
            String matchId = repo.resolveMatchId(PROVIDER, providerMatchId).orElse(null);
            if (matchId == null) {
                log.warn("No mapping for {} match {}; skipping", PROVIDER, providerMatchId);
                continue;
            }
            poll(matchId, providerMatchId, "score", () -> pollScore(providerMatchId));
            poll(matchId, providerMatchId, "commentary", () -> pollCommentary(providerMatchId));
        }
    }

    private void pollScore(String providerMatchId) {
        ProviderBScoreResponse body = validate(client.fetchScore(providerMatchId));
        checkMatchId(providerMatchId, body.providerMatchId());
        boolean applied = ingestion.ingestSnapshot(PROVIDER, providerMatchId, body.snapshot().toSnapshotData());
        log.debug("Polled score {} (seq={}, rev={}): applied={}", providerMatchId,
                body.snapshot().sequence(), body.snapshot().revision(), applied);
    }

    private void pollCommentary(String providerMatchId) {
        ProviderBCommentaryResponse body = validate(client.fetchCommentary(providerMatchId));
        checkMatchId(providerMatchId, body.providerMatchId());
        int changed = ingestion.ingestCommentary(PROVIDER, providerMatchId,
                body.items().stream().map(ProviderBCommentaryResponse.Item::toBallData).toList());
        log.debug("Polled commentary {}: {} items, {} changed", providerMatchId, body.items().size(), changed);
    }

    private void poll(String matchId, String providerMatchId, String what, Runnable call) {
        try {
            call.run();
            repo.recordSuccess(matchId, PROVIDER);
        } catch (RuntimeException e) {
            String error = what + ": " + e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("Poll of {} {} failed; keeping last good state. {}", PROVIDER, providerMatchId, error);
            repo.recordError(matchId, PROVIDER, error.length() <= 500 ? error : error.substring(0, 500));
        }
    }

    private <T> T validate(T body) {
        if (body == null) {
            throw new IllegalStateException("empty response body");
        }
        Set<ConstraintViolation<T>> violations = validator.validate(body);
        if (!violations.isEmpty()) {
            ConstraintViolation<T> first = violations.iterator().next();
            throw new IllegalStateException("invalid response: " + first.getPropertyPath() + " " + first.getMessage());
        }
        return body;
    }

    private static void checkMatchId(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException("response is for match " + actual + ", expected " + expected);
        }
    }
}
