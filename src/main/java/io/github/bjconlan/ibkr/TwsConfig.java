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
 */
public record TwsConfig(
        String host,
        int port,
        int clientId,
        String optionalCapabilities,
        Duration connectTimeout,
        int connectAttempts,
        Duration initialBackoff) {

    public TwsConfig {
        if (connectAttempts < 1) {
            throw new IllegalArgumentException("connectAttempts must be >= 1");
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
                Duration.ofMillis(500));
    }

    public TwsConfig withHost(String host) {
        return new TwsConfig(host, port, clientId, optionalCapabilities, connectTimeout, connectAttempts, initialBackoff);
    }

    public TwsConfig withPort(int port) {
        return new TwsConfig(host, port, clientId, optionalCapabilities, connectTimeout, connectAttempts, initialBackoff);
    }

    public TwsConfig withClientId(int clientId) {
        return new TwsConfig(host, port, clientId, optionalCapabilities, connectTimeout, connectAttempts, initialBackoff);
    }
}
