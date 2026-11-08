package com.fancode.aggregator;

/** Builders for Provider A webhook bodies and Provider B poll bodies used across tests. */
public final class Events {

    private Events() {
    }

    public static String snapshot(long seq, int rev, String status, int runs, int wickets, String overs) {
        return """
                {"sequence":%d,"revision":%d,"status":"%s","innings":1,"runs":%d,"wickets":%d,"overs":"%s"}"""
                .formatted(seq, rev, status, runs, wickets, overs);
    }

    public static String snapshot(long seq, int rev, int runs, int wickets, String overs) {
        return snapshot(seq, rev, "live", runs, wickets, overs);
    }

    public static String ball(String ballId, long seq, int rev, String text) {
        return """
                {"ballId":"%s","sequence":%d,"revision":%d,"innings":1,"over":0,"ball":%d,"text":"%s"}"""
                .formatted(ballId, seq, rev, seq, text);
    }

    /** A Provider A webhook event. snapshotJson / ballJson may be null to omit that part. */
    public static String push(String deliveryId, String snapshotJson, String ballJson) {
        StringBuilder sb = new StringBuilder()
                .append("{\"provider\":\"provider-a\",\"deliveryId\":\"").append(deliveryId).append('"')
                .append(",\"occurredAt\":\"2026-10-22T14:03:11.000Z\"")
                .append(",\"providerMatchId\":\"a-match-1001\"");
        if (snapshotJson != null) {
            sb.append(",\"snapshot\":").append(snapshotJson);
        }
        if (ballJson != null) {
            sb.append(",\"commentary\":").append(ballJson);
        }
        return sb.append('}').toString();
    }

    /** A Provider B score response. */
    public static String pollScore(long seq, int rev, int runs, int wickets, String overs, String asOf) {
        return """
                {"provider":"provider-b","providerMatchId":"b-match-1001","snapshot":
                {"sequence":%d,"revision":%d,"status":"live","innings":1,"runs":%d,"wickets":%d,"overs":"%s","asOf":"%s"}}"""
                .formatted(seq, rev, runs, wickets, overs, asOf);
    }

    /** A Provider B commentary response containing the given item JSON objects. */
    public static String pollCommentary(String... items) {
        return "{\"provider\":\"provider-b\",\"providerMatchId\":\"b-match-1001\",\"items\":["
                + String.join(",", items) + "]}";
    }
}
