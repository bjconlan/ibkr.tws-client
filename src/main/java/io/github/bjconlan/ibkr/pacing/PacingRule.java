package io.github.bjconlan.ibkr.pacing;

import java.time.Duration;

/**
 * A declarative description of one Interactive Brokers pacing limit. Rules are data:
 * they are attached to {@link RequestType}s in {@link PacingRules} and interpreted by
 * {@link Pacer}. Keeping them as values makes the documented limits reviewable in one
 * place instead of being scattered as ad-hoc sleeps.
 */
public sealed interface PacingRule {

    /** A stable name used to share limit state across requests of the same type. */
    String name();

    /**
     * At most {@code permits} requests of the owning type may start per {@code period},
     * measured across all callers.
     */
    record Rate(String name, int permits, Duration period) implements PacingRule {}

    /**
     * At most {@code permits} requests may start per {@code period} for each distinct
     * request fingerprint. This models rules such as "identical historical data requests
     * may not repeat within 15 seconds" or "at most 6 requests for the same contract
     * within 2 seconds".
     */
    record KeyedRate(String name, int permits, Duration period) implements PacingRule {}

    /**
     * At most {@code permits} requests of the owning type may be outstanding at once.
     * A permit is held until the caller releases the returned lease - this models
     * subscription style limits such as the number of simultaneous market data lines.
     */
    record Concurrency(String name, int permits) implements PacingRule {}
}
