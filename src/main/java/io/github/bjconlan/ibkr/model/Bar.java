package io.github.bjconlan.ibkr.model;

/** One historical data bar. */
public record Bar(
        String date,
        double open,
        double high,
        double low,
        double close,
        String volume,
        String weightedAveragePrice,
        Integer barCount) {
}
