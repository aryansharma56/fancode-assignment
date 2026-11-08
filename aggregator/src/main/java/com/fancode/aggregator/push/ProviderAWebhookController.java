package com.fancode.aggregator.push;

import com.fancode.aggregator.ingest.IngestionService;
import com.fancode.aggregator.ingest.IngestionService.PushResult;
import com.fancode.aggregator.ingest.Providers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Provider A webhook.
 *
 * <p>Response contract:
 * <ul>
 *   <li>200 {"result":"accepted"} - the delivery and its effects are committed.</li>
 *   <li>200 {"result":"duplicate"} - this deliveryId was already committed earlier; nothing changed.</li>
 *   <li>400 / 422 - permanently rejected (malformed, unknown match). Do not retry.</li>
 *   <li>5xx or no response - not known to be stored. Retry with the same deliveryId.</li>
 * </ul>
 * The 200 is only sent after the transaction commits, so a 200 always means "durably stored".
 */
@RestController
public class ProviderAWebhookController {

    public record WebhookResponse(String result, String deliveryId, String matchId,
                                  boolean snapshotApplied, boolean commentaryApplied) {
    }

    private final IngestionService ingestion;
    private final ObjectMapper objectMapper;

    public ProviderAWebhookController(IngestionService ingestion, ObjectMapper objectMapper) {
        this.ingestion = ingestion;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhooks/provider-a")
    public WebhookResponse receive(@Valid @RequestBody ProviderAEvent event) {
        if (!Providers.PROVIDER_A.equals(event.provider())) {
            throw new IllegalArgumentException("provider must be '" + Providers.PROVIDER_A + "'");
        }
        if (event.snapshot() == null && event.commentary() == null) {
            throw new IllegalArgumentException("event must contain a snapshot, commentary, or both");
        }

        PushResult result = ingestion.ingestPush(Providers.PROVIDER_A, event.deliveryId(), hash(event),
                event.providerMatchId(), event.toSnapshotData(), event.toBallData());

        return new WebhookResponse(result.outcome().name().toLowerCase(), event.deliveryId(),
                result.matchId(), result.snapshotApplied(), result.commentaryApplied());
    }

    /** Hash of the parsed event, used only to log a deliveryId that is reused with a different body. */
    private String hash(ProviderAEvent event) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(event);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
