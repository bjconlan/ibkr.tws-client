package io.github.bjconlan.ibkr.pacing;

import io.github.bjconlan.ibkr.protocol.OutgoingId;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The pacing behaviour Interactive Brokers documents for the TWS API, expressed as data.
 *
 * <p>Only rules that appear in IBKR's documentation are modelled. The aggregate request limit
 * scales with the account's market data lines, so {@link #standard(int)} takes that count (see
 * {@link io.github.bjconlan.ibkr.TwsConfig#marketDataLines()}).
 *
 * <p>Sources:
 * <ul>
 *   <li><a href="https://www.interactivebrokers.com/docs/tws-api/doc/pacing-limitations/introduction">Pacing
 *       Limitations &rarr; Introduction</a> - "The maximum number of API requests that can be
 *       submitted are equivalent to your Maximum Market Data Lines divided by 2, per second."
 *       Default 100 lines therefore allows 50 requests per second, and the limit scales with the
 *       entitlement. It is a per-connection aggregate over all request types.</li>
 *   <li><a href="https://www.interactivebrokers.com/docs/tws-api/doc/market-data-historical/historical-data-limitations/pacing-violations-for-small-bars-30-secs-or-less">Pacing
 *       Violations for Small Bars</a> - identical historical requests within 15 seconds; six or
 *       more historical requests for the same contract, exchange and tick type within two
 *       seconds; more than 60 requests in any ten minute period; BID_ASK counts twice.</li>
 *   <li><a href="https://www.interactivebrokers.com/docs/general/market-data-subscriptions/market-data-lines/how-market-data-is-allocated">How
 *       Market Data is Allocated</a> - 100 concurrent market data lines minimum, scaling with
 *       commissions and equity.</li>
 *   <li><a href="https://www.interactivebrokers.com/docs/tws-api/doc/market-data-live/tick-by-tick-data/request-tick-by-tick-data">Request
 *       Tick By Tick Data</a> - simultaneous tick-by-tick subscriptions are capped at 5% of the
 *       user's market data lines.</li>
 * </ul>
 *
 * <p>Rules are deliberately absent for order placement and execution requests: the current
 * documentation defines no per-type limit for them, so they are governed solely by the aggregate
 * limit. Callers who want a stricter policy can build their own map and pass a {@link Pacer} to
 * {@link io.github.bjconlan.ibkr.TwsClient}.
 */
public final class PacingRules {

    private PacingRules() {
    }

    /**
     * The aggregate per-connection request limit: market data lines divided by two, per second,
     * floored at one. Applied to every request before any type-specific rule.
     */
    public static List<PacingRule> global(int marketDataLines) {
        int lines = Math.max(1, marketDataLines);
        return List.of(new PacingRule.Rate("ib.pacing.requests", Math.max(1, lines / 2), Duration.ofSeconds(1)));
    }

    /**
     * Default per-request limits for the documented constraints. {@code marketDataLines} is the
     * account's maximum market data lines (100 by default; see
     * {@link io.github.bjconlan.ibkr.TwsConfig#marketDataLines()}).
     */
    public static Map<OutgoingId, List<PacingRule>> standard(int marketDataLines) {
        int lines = Math.max(1, marketDataLines);
        return Map.of(
                OutgoingId.REQ_HISTORICAL_DATA, List.of(
                        new PacingRule.Rate("ib.historical.60-per-10m", 60, Duration.ofMinutes(10)),
                        new PacingRule.KeyedRate("ib.historical.identical-15s", 1, Duration.ofSeconds(15),
                                PacingRule.KeyedRate.Scope.REQUEST),
                        new PacingRule.KeyedRate("ib.historical.same-contract-6-per-2s", 6, Duration.ofSeconds(2),
                                PacingRule.KeyedRate.Scope.CONTRACT_EXCHANGE_TICK_TYPE)),

                OutgoingId.REQ_MKT_DATA, List.of(
                        new PacingRule.Concurrency("ib.marketdata.lines", lines)),

                OutgoingId.REQ_TICK_BY_TICK_DATA, List.of(
                        new PacingRule.Concurrency("ib.tickbytick.subscriptions", Math.max(1, lines / 20))));
    }
}
