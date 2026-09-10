package io.github.bjconlan.ibkr.pacing;

import io.github.bjconlan.ibkr.protocol.OutgoingId;

/**
 * Thrown when a request cannot be admitted without breaching a documented Interactive
 * Brokers pacing limit within the configured wait budget. The request has not been sent.
 */
public class PacingViolationException extends RuntimeException {

    private final String rule;
    private final OutgoingId id;

    public PacingViolationException(String rule, OutgoingId id, String message) {
        super(message);
        this.rule = rule;
        this.id = id;
    }

    /** The name of the {@link PacingRule} that could not be satisfied. */
    public String rule() {
        return rule;
    }

    /** The outbound request that was rejected. */
    public OutgoingId id() {
        return id;
    }
}
