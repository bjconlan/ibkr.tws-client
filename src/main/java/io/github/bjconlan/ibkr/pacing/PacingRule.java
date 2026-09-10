package io.github.bjconlan.ibkr.pacing;

import java.time.Duration;

/**
 * A declarative description of one Interactive Brokers pacing limit. Rules are data: they are
 * attached to {@link io.github.bjconlan.ibkr.protocol.OutgoingId}s in {@link PacingRules} and
 * interpreted by {@link Pacer}. Keeping them as values makes the documented limits reviewable in
 * one place instead of being scattered as ad-hoc sleeps.
 */
public sealed interface PacingRule {

    /** A stable name used to share limit state across requests of the same kind. */
    String name();

    /**
     * At most {@code permits} requests sharing this rule may start per {@code period},
     * measured across all callers.
     */
    record Rate(String name, int permits, Duration period) implements PacingRule {}

    /**
     * At most {@code permits} requests may start per {@code period} for each distinct value of
     * the key named by {@link #scope()}.
     *
     * <p>This models rules such as "identical historical data requests may not repeat within
     * 15 seconds" ({@link Scope#REQUEST}) and "at most 6 requests for the same contract,
     * exchange and tick type within 2 seconds" ({@link Scope#CONTRACT_EXCHANGE_TICK_TYPE}).
     */
    record KeyedRate(String name, int permits, Duration period, Scope scope) implements PacingRule {

        /** Which part of a request the limit is keyed on. */
        public enum Scope {
            /** The full request, e.g. contract, date range, bar size and tick type. */
            REQUEST,
            /** Contract, exchange and tick type only, ignoring date range and bar size. */
            CONTRACT_EXCHANGE_TICK_TYPE
        }
    }

    /**
     * At most {@code permits} subscriptions of the owning kind may be outstanding at once.
     * A permit is held until the caller releases the returned lease - this models
     * subscription style limits such as simultaneous market data lines.
     */
    record Concurrency(String name, int permits) implements PacingRule {}
}
