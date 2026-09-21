package io.github.bjconlan.ibkr;

import io.github.bjconlan.ibkr.pacing.Pacer;
import io.github.bjconlan.ibkr.pacing.SubscriptionBudget;
import io.github.bjconlan.ibkr.transport.EventHandler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * A pool of {@link TwsConnection}s, one per client id the caller reserves for this service,
 * defaulting to {@code IntStream.range(1, 32)} (1..31) when none are given.
 *
 * <p>Every pooled connection gets its own {@link Pacer} <em>rate</em> budget - IBKR's aggregate
 * request rate is per connection - but they share one account-wide {@link SubscriptionBudget}, so
 * the market data line and tick-by-tick caps stay account-wide even with several connections.
 *
 * <p>Connections are opened <em>lazily</em>: nothing is dialled until a request is made, and each
 * reserved client id is dialled the first time the round-robin selects it. Creating a session does
 * no I/O.
 *
 * <p>{@link #createSession()} returns a {@link TwsSession}. Sessions do not lease a connection:
 * each request opened through a session is placed on a live connection round-robin, and the
 * session tracks its handles so {@link TwsSession#close()} cancels them all. That keeps request-id
 * allocation and routing per connection while letting many scopes multiplex over few sockets.
 *
 * <p>There is no automatic reconnect: a dropped connection is skipped by the round-robin while
 * others remain live; reconnect policy is left to the caller, as for a single
 * {@link TwsConnection}.
 */
public final class TwsClientFactory implements AutoCloseable {

    private final TwsConfig template;
    private final EventHandler handler;
    private final List<Integer> clientIds;
    private final SubscriptionBudget budget = new SubscriptionBudget();
    private final Map<Integer, TwsConnection> connections = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private final AtomicInteger next = new AtomicInteger();

    private volatile boolean closed;

    /**
     * @param template  connection settings; its client id is overridden per pooled connection
     * @param handler   connection-level event handler, shared by all pooled connections
     * @param clientIds the client ids this service owns; reserved ids are simply omitted
     */
    public TwsClientFactory(TwsConfig template, EventHandler handler, List<Integer> clientIds) {
        this.template = template;
        this.handler = handler;
        this.clientIds = List.copyOf(clientIds);
        if (this.clientIds.isEmpty()) {
            throw new IllegalArgumentException("at least one client id is required");
        }
    }

    /**
     * A pool over the default client id range, {@code IntStream.range(1, 32)} (1..31). Use the
     * three-argument constructor to reserve an explicit list and avoid ids used by other clients.
     */
    public TwsClientFactory(TwsConfig template, EventHandler handler) {
        this(template, handler, IntStream.range(1, 32).boxed().toList());
    }

    /** The client ids this pool owns, in order. */
    public List<Integer> clientIds() {
        return clientIds;
    }

    /**
     * Creates a request scope over the pool. No connection is opened until the first request;
     * requests are distributed round-robin across the live connections, dialling a reserved client
     * id the first time it is selected.
     */
    public TwsSession createSession() {
        if (closed) {
            throw new IllegalStateException("factory is closed");
        }
        return new TwsSession(this::nextConnection);
    }

    /** Whether any dialled connection is currently open. */
    public boolean isConnected() {
        return connections.values().stream().anyMatch(TwsConnection::isConnected);
    }

    private TwsConnection nextConnection() {
        int size = clientIds.size();
        for (int attempt = 0; attempt < size; attempt++) {
            int index = Math.floorMod(next.getAndIncrement(), size);
            TwsConnection connection = connection(clientIds.get(index));
            if (connection.isConnected()) {
                return connection;
            }
        }
        throw new IllegalStateException("no live connection in the pool");
    }

    private TwsConnection connection(int clientId) {
        TwsConnection existing = connections.get(clientId);
        if (existing != null) {
            return existing;
        }
        if (closed) {
            throw new IllegalStateException("factory is closed");
        }
        synchronized (lock) {
            existing = connections.get(clientId);
            if (existing == null) {
                existing = connect(clientId);
                connections.put(clientId, existing);
            }
            return existing;
        }
    }

    private TwsConnection connect(int clientId) {
        try {
            return TwsConnection.open(
                    template.withClientId(clientId), handler,
                    Pacer.of(template.marketDataLines(), budget));
        } catch (IOException e) {
            throw new UncheckedIOException("could not connect client id " + clientId, e);
        }
    }

    @Override
    public void close() {
        closed = true;
        connections.values().forEach(TwsConnection::close);
        connections.clear();
    }
}
