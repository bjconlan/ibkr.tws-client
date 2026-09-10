package io.github.bjc.ibkr.model;

/** A single account value update, for example {@code NetLiquidation} or {@code AvailableFunds}. */
public record AccountValue(
        String key,
        String value,
        String currency,
        String accountName) {
}
