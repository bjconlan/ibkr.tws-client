package io.github.bjconlan.ibkr.pacing;

import io.github.resilience4j.bulkhead.Bulkhead;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Account-wide subscription concurrency, shared across the connections of a pool.
 *
 * <p>IBKR's aggregate request rate is <em>per connection</em>, but the market data line count and
 * the tick-by-tick cap are <em>per account</em>. So each connection in a pool gets its own
 * {@link Pacer} for rate (a pool of N multiplies the rate budget) while sharing one
 * {@code SubscriptionBudget}, keeping the subscription caps account-wide. A single-connection
 * client can ignore this and use {@link Pacer#of(int)}.
 */
public final class SubscriptionBudget {

    private final Map<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();

    Map<String, Bulkhead> bulkheads() {
        return bulkheads;
    }
}
