package com.fancode.aggregator.web;

/** A provider sent data for a provider match id we have no mapping for. */
public class UnknownProviderMatchException extends RuntimeException {

    public UnknownProviderMatchException(String provider, String providerMatchId) {
        super("Unknown match '" + providerMatchId + "' for " + provider);
    }
}
