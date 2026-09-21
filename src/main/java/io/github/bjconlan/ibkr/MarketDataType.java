package io.github.bjconlan.ibkr;

/**
 * The market data type requested from TWS, sent with {@code REQ_MARKET_DATA_TYPE}.
 *
 * <p>Codes mirror the upstream API's integer constants, which are otherwise opaque at the call
 * site.
 *
 * @see <a href="https://ibkrcampus.com/docs/tws-api/doc/market-data-delayed/market-data-type-behavior">Market Data Type Behavior</a>
 */
public enum MarketDataType {

    /** Real-time data, if the account is entitled. */
    LIVE(1),
    /** The last available real-time snapshot, frozen until the next request. */
    FROZEN(2),
    /** Delayed data, if entitled to it. */
    DELAYED(3),
    /** The last available delayed snapshot, frozen until the next request. */
    DELAYED_FROZEN(4);

    private final int code;

    MarketDataType(int code) {
        this.code = code;
    }

    /** The upstream integer constant. */
    public int code() {
        return code;
    }
}
