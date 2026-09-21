package io.github.bjconlan.ibkr;

import io.github.bjconlan.ibkr.event.IbEvent;

/**
 * Raised when TWS reports an error against a specific request id. A request-scoped error is
 * treated as terminal: the {@code submit} future completes exceptionally with this, and the
 * {@code stream} iterator throws it wrapped in a {@link java.util.concurrent.CompletionException}.
 */
public final class IbRequestException extends RuntimeException {

    private final int requestId;
    private final int code;
    private final long epochMillis;
    private final String advancedOrderRejectJson;

    public IbRequestException(IbEvent.Error error) {
        super("error %d for request %d: %s".formatted(error.code(), error.requestId(), error.message()));
        this.requestId = error.requestId();
        this.code = error.code();
        this.epochMillis = error.epochMillis();
        this.advancedOrderRejectJson = error.advancedOrderRejectJson();
    }

    /** The request the error applies to. */
    public int requestId() {
        return requestId;
    }

    /** The TWS error code, e.g. 200 for an unknown contract. */
    public int code() {
        return code;
    }

    /** When TWS raised the error, in epoch milliseconds. */
    public long epochMillis() {
        return epochMillis;
    }

    /** The advanced order rejection payload, usually {@code null}. */
    public String advancedOrderRejectJson() {
        return advancedOrderRejectJson;
    }
}
