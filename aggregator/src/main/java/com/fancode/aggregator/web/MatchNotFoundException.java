package com.fancode.aggregator.web;

public class MatchNotFoundException extends RuntimeException {

    public MatchNotFoundException(String matchId) {
        super("Match '" + matchId + "' not found");
    }
}
