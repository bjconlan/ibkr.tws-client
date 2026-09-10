package io.github.bjc.ibkr.pacing;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;

/**
 * The pacing behaviour Interactive Brokers documents for the TWS API, expressed as data.
 *
 * <p>The defaults are deliberately conservative: the global limit is set a little below the
 * advertised ceiling so that bursts of bookkeeping traffic (cancels, subscriptions) do not
 * push a busy client over the edge. Every value is a plain {@link PacingRule} so callers can
 * build their own map and hand it to {@link Pacer} instead of guessing.
 *
 * <p>Sources for the limits below are the TWS API "Pacing Violations" documentation:
 * <ul>
 *   <li>all API messages: 50 per second,</li>
 *   <li>historical data: 60 requests per 10 minutes, no identical request within 15 seconds,
 *       no more than 6 requests for the same contract/exchange/tick type within 2 seconds,</li>
 *   <li>simultaneous market data lines: 100 (a tick-by-tick subscription consumes several),</li>
 *   <li>order messages: 50 per second.</li>
 * </ul>
 */
public final class PacingRules {

    private PacingRules() {
    }

    /** Applied to every request, before any type-specific rule. */
    public static final List<PacingRule> GLOBAL = List.of(
            // IBKR allows 50 messages/second. Stay just under it.
            new PacingRule.Rate("ib.global", 45, Duration.ofSeconds(1)));

    /** Default per-type limits. Types not present here have no type-specific limit. */
    public static Map<RequestType, List<PacingRule>> defaults() {
        return Map.ofEntries(
                entry(RequestType.HISTORICAL_DATA, List.of(
                        new PacingRule.Rate("ib.historical.60-per-10m", 60, Duration.ofMinutes(10)),
                        new PacingRule.KeyedRate("ib.historical.identical-15s", 1, Duration.ofSeconds(15)),
                        new PacingRule.KeyedRate("ib.historical.same-contract-6-per-2s", 6, Duration.ofSeconds(2)))),

                entry(RequestType.MARKET_DATA, List.of(
                        new PacingRule.Concurrency("ib.marketdata.lines", 100))),

                entry(RequestType.PLACE_ORDER, List.of(
                        new PacingRule.Rate("ib.orders.50-per-sec", 45, Duration.ofSeconds(1)))),

                entry(RequestType.CANCEL_ORDER, List.of(
                        new PacingRule.Rate("ib.orders.cancel.50-per-sec", 45, Duration.ofSeconds(1)))),

                // Widely reported as one request per 15 seconds per client; conservative default.
                entry(RequestType.EXECUTIONS, List.of(
                        new PacingRule.KeyedRate("ib.executions.1-per-15s", 1, Duration.ofSeconds(15)))));
    }
}
