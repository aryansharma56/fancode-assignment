package com.fancode.aggregator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "aggregator")
public record AggregatorProperties(String primaryProvider, ProviderB providerB) {

    public record ProviderB(
            boolean enabled,
            String baseUrl,
            List<String> matchIds,
            long pollIntervalMs,
            int connectTimeoutMs,
            int readTimeoutMs) {
    }
}
