package io.github.bjc.ibkr.transport;

import io.github.bjc.ibkr.event.IbEvent;

/**
 * Receives server events on a single dispatcher thread. Handlers should not assume they are on
 * the caller's thread; they may block, and if they do, subsequent events are queued rather than
 * dropped.
 */
@FunctionalInterface
public interface EventHandler {

    void onEvent(IbEvent event);
}
