package io.github.bjc.ibkr.pacing;

/**
 * The kinds of outbound request the client can make. Each type maps to a set of
 * {@link PacingRule}s in {@link PacingRules} that describe the limits Interactive
 * Brokers documents for that request.
 */
public enum RequestType {
    START_API,
    CURRENT_TIME,
    IDS,
    CONTRACT_DETAILS,
    MARKET_DATA,
    CANCEL_MARKET_DATA,
    HISTORICAL_DATA,
    CANCEL_HISTORICAL_DATA,
    PLACE_ORDER,
    CANCEL_ORDER,
    OPEN_ORDERS,
    POSITIONS,
    CANCEL_POSITIONS,
    EXECUTIONS,
    ACCOUNT_UPDATES,
    MARKET_DATA_TYPE
}
