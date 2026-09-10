package io.github.bjconlan.ibkr.pacing;

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
    MARKET_DATA_TYPE,

    /** Any request without a type-specific pacing rule; only the global limit applies. */
    OTHER;

    /** Maps an outbound id to the pacing type used for its rule lookup. */
    public static RequestType of(io.github.bjconlan.ibkr.protocol.OutgoingId id) {
        return switch (id) {
            case START_API -> START_API;
            case REQ_CURRENT_TIME -> CURRENT_TIME;
            case REQ_IDS -> IDS;
            case REQ_CONTRACT_DATA, CANCEL_CONTRACT_DATA -> CONTRACT_DETAILS;
            case REQ_MKT_DATA -> MARKET_DATA;
            case CANCEL_MKT_DATA -> CANCEL_MARKET_DATA;
            case REQ_HISTORICAL_DATA, REQ_HISTORICAL_TICKS, REQ_HEAD_TIMESTAMP,
                 REQ_HISTOGRAM_DATA -> HISTORICAL_DATA;
            case CANCEL_HISTORICAL_DATA, CANCEL_HISTORICAL_TICKS -> CANCEL_HISTORICAL_DATA;
            case PLACE_ORDER -> PLACE_ORDER;
            case CANCEL_ORDER -> CANCEL_ORDER;
            case REQ_OPEN_ORDERS -> OPEN_ORDERS;
            case REQ_POSITIONS -> POSITIONS;
            case CANCEL_POSITIONS -> CANCEL_POSITIONS;
            case REQ_EXECUTIONS -> EXECUTIONS;
            case REQ_ACCOUNT_DATA -> ACCOUNT_UPDATES;
            case REQ_MARKET_DATA_TYPE -> MARKET_DATA_TYPE;
            default -> OTHER;
        };
    }
}
