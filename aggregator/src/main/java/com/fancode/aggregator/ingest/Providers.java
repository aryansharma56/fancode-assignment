package com.fancode.aggregator.ingest;

public final class Providers {

    public static final String PROVIDER_A = "provider-a";
    public static final String PROVIDER_B = "provider-b";

    /** Allowed match statuses, as a regex for bean validation on provider payloads. */
    public static final String STATUS_PATTERN = "scheduled|live|innings_break|completed|abandoned";

    private Providers() {
    }
}
