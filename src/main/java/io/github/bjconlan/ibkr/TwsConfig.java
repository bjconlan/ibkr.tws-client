package io.github.bjconlan.ibkr;

import java.time.Duration;

/**
 * Connection settings. Fault-tolerance settings are deliberately explicit so the retry policy
 * is visible at the call site rather than hidden inside the transport.
 *
 * @param host              TWS or IB Gateway host
 * @param port              TWS/Gateway port; defaults to the paper-trading port 7497
 * @param clientId          API client id
 * @param optionalCapabilities optional capabilities string, usually empty
 * @param connectTimeout    socket connect timeout
 * @param connectAttempts   total connection attempts, including the first
 * @param initialBackoff    first retry delay; subsequent attempts back off exponentially
 * @param marketDataLines   account's maximum market data lines; drives the aggregate pacing
 *                          limit (lines / 2 requests per second) and the market data and
 *                          tick-by-tick concurrency limits. Defaults to 100
 * @param marketDataType    market data type requested when the connection opens; {@code null}
 *                          sends nothing, leaving the TWS/Gateway-configured type in force
 */
public record TwsConfig(
        String host,
        int port,
        int clientId,
        String optionalCapabilities,
        Duration connectTimeout,
        int connectAttempts,
        Duration initialBackoff,
        int marketDataLines,
        MarketDataType marketDataType) {

    public TwsConfig {
        if (connectAttempts < 1) {
            throw new IllegalArgumentException("connectAttempts must be >= 1");
        }
        if (marketDataLines < 1) {
            throw new IllegalArgumentException("marketDataLines must be >= 1");
        }
    }

    /** Sensible defaults for a local paper-trading TWS. */
    public static TwsConfig defaults(int clientId) {
        return new TwsConfig(
                "127.0.0.1",
                7497,
                clientId,
                "",
                Duration.ofSeconds(10),
                3,
                Duration.ofMillis(500),
                100,
                null);
    }

    public TwsConfig withHost(String host) {
        return new TwsConfig(host, port, clientId, optionalCapabilities, connectTimeout, connectAttempts,
                initialBackoff, marketDataLines, marketDataType);
    }

    public TwsConfig withPort(int port) {
        return new TwsConfig(host, port, clientId, optionalCapabilities, connectTimeout, connectAttempts,
                initialBackoff, marketDataLines, marketDataType);
    }

    public TwsConfig withClientId(int clientId) {
        return new TwsConfig(host, port, clientId, optionalCapabilities, connectTimeout, connectAttempts,
                initialBackoff, marketDataLines, marketDataType);
    }

    /**
     * Sets the account's market data line entitlement, which scales the aggregate request limit
     * and the market data subscriptions this client is willing to open.
     */
    public TwsConfig withMarketDataLines(int marketDataLines) {
        return new TwsConfig(host, port, clientId, optionalCapabilities, connectTimeout, connectAttempts,
                initialBackoff, marketDataLines, marketDataType);
    }

    /**
     * Sets the market data type requested when the connection opens. The default is {@code null},
     * which sends nothing and leaves the TWS/Gateway-configured type in force - the safer choice
     * for accounts without a live entitlement.
     */
    public TwsConfig withMarketDataType(MarketDataType marketDataType) {
        return new TwsConfig(host, port, clientId, optionalCapabilities, connectTimeout, connectAttempts,
                initialBackoff, marketDataLines, marketDataType);
    }
}
