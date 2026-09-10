package io.github.bjc.ibkr.pacing;

/**
 * Thrown when a request cannot be admitted without breaching a documented Interactive
 * Brokers pacing limit within the configured wait budget. The request has not been sent.
 */
public class PacingViolationException extends RuntimeException {

    private final String rule;
    private final RequestType type;

    public PacingViolationException(String rule, RequestType type, String message) {
        super(message);
        this.rule = rule;
        this.type = type;
    }

    /** The name of the {@link PacingRule} that could not be satisfied. */
    public String rule() {
        return rule;
    }

    /** The request type that was rejected. */
    public RequestType type() {
        return type;
    }
}
