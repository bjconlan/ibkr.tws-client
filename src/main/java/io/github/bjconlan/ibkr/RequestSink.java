package io.github.bjconlan.ibkr;

import io.github.bjconlan.ibkr.event.IbEvent;

/**
 * Internal: receives the correlated responses of one in-flight request. Implemented by the session
 * façades ({@code submit}, {@code stream}, {@code on}); not part of the public API.
 */
interface RequestSink {

    /** A response belonging to this request. */
    void message(IbEvent.Message message);

    /** The request's terminal message arrived. */
    void terminal();

    /** The request failed, e.g. a request-scoped error or a dropped connection. */
    void error(Throwable cause);

    /** The request was cancelled locally. */
    void cancelled();
}
