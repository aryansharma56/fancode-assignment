package com.fancode.aggregator.poll;

import com.fancode.aggregator.config.AggregatorProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** HTTP client for the Provider B poll endpoints. Any non-2xx response throws. */
@Component
public class ProviderBClient {

    private final RestClient http;

    public ProviderBClient(RestClient.Builder builder, AggregatorProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.providerB().connectTimeoutMs());
        factory.setReadTimeout(props.providerB().readTimeoutMs());
        this.http = builder
                .baseUrl(props.providerB().baseUrl())
                .requestFactory(factory)
                .build();
    }

    public ProviderBScoreResponse fetchScore(String providerMatchId) {
        return http.get()
                .uri("/provider/matches/{id}/score", providerMatchId)
                .retrieve()
                .body(ProviderBScoreResponse.class);
    }

    public ProviderBCommentaryResponse fetchCommentary(String providerMatchId) {
        return http.get()
                .uri("/provider/matches/{id}/commentary", providerMatchId)
                .retrieve()
                .body(ProviderBCommentaryResponse.class);
    }
}
