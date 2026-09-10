package io.github.bjconlan.ibkr;

import io.github.bjconlan.ibkr.event.IbEvent;
import io.github.bjconlan.ibkr.model.Contract;
import io.github.bjconlan.ibkr.model.ExecutionFilter;
import io.github.bjconlan.ibkr.model.Order;
import io.github.bjconlan.ibkr.pacing.Pacer;
import io.github.bjconlan.ibkr.pacing.RequestType;
import io.github.bjconlan.ibkr.protocol.Encoder;
import io.github.bjconlan.ibkr.transport.EventHandler;
import io.github.bjconlan.ibkr.transport.Transport;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal, thread-safe TWS API client.
 *
 * <p>Request methods are synchronous: they may block on the {@link Pacer} until it is safe to send
 * (virtual threads make this cheap), then hand the frame to the transport. Server messages arrive
 * as {@link IbEvent}s on the {@link EventHandler} passed to the constructor, in order.
 *
 * <p>Market data lines are tracked as held permits: one is taken per subscription and returned
 * when the subscription is cancelled, when a snapshot completes, or when the connection drops.
 */
public final class TwsClient implements AutoCloseable {

    private final TwsConfig config;
    private final EventHandler handler;
    private final Pacer pacer;

    private final AtomicInteger reqIdSeq = new AtomicInteger(1);
    private final Map<Integer, Pacer.Lease> marketDataLeases = new ConcurrentHashMap<>();

    private volatile Transport transport;

    public TwsClient(TwsConfig config, EventHandler handler) {
        this(config, handler, Pacer.standard());
    }

    public TwsClient(TwsConfig config, EventHandler handler, Pacer pacer) {
        this.config = config;
        this.handler = handler;
        this.pacer = pacer;
    }

    // ------------------------------------------------------------------ lifecycle

    public TwsConfig config() {
        return config;
    }

    public boolean isConnected() {
        Transport t = transport;
        return t != null && t.isOpen();
    }

    public int serverVersion() {
        Transport t = transport;
        return t == null ? 0 : t.serverVersion();
    }

    /**
     * Connects, retrying transient failures with exponential backoff, then starts the API so
     * TWS begins delivering {@code ManagedAccounts} and {@code NextValidId}.
     */
    public void connect() throws IOException {
        Retry retry = Retry.of("ibkr-connect", RetryConfig.<Transport>custom()
                .maxAttempts(config.connectAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(config.initialBackoff()))
                .retryExceptions(UncheckedIOException.class)
                .build());

        try {
            Transport opened = Retry.decorateSupplier(retry, () -> {
                        try {
                            return Transport.open(config.host(), config.port(),
                                    (int) config.connectTimeout().toMillis(), this::dispatch);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .get();
            this.transport = opened;
            send(RequestType.START_API, null,
                    Encoder.startApi(config.clientId(), config.optionalCapabilities()));
        } catch (RuntimeException e) {
            throw new IOException("could not connect to %s:%d".formatted(config.host(), config.port()), e);
        }
    }

    @Override
    public void close() {
        Transport t = transport;
        transport = null;
        if (t != null) {
            t.close();
        }
        releaseAllMarketData();
    }

    /** The next unused request id. */
    public int nextReqId() {
        return reqIdSeq.getAndIncrement();
    }

    // ------------------------------------------------------------------ requests

    public void reqCurrentTime() {
        send(RequestType.CURRENT_TIME, null, Encoder.reqCurrentTime());
    }

    public void reqIds() {
        send(RequestType.IDS, null, Encoder.reqIds());
    }

    public void reqContractDetails(int reqId, Contract contract) {
        send(RequestType.CONTRACT_DETAILS, fingerprint(contract),
                Encoder.reqContractDetails(reqId, contract));
    }

    public void cancelContractDetails(int reqId) {
        send(RequestType.CONTRACT_DETAILS, null, Encoder.cancelContractDetails(reqId));
    }

    public void reqMktData(int reqId, Contract contract, String genericTickList,
                           boolean snapshot, boolean regulatorySnapshot) {
        Pacer.Lease lease = pacer.acquireLease(RequestType.MARKET_DATA);
        try {
            send(RequestType.MARKET_DATA, null,
                    Encoder.reqMktData(reqId, contract, genericTickList, snapshot, regulatorySnapshot));
            marketDataLeases.put(reqId, lease);
        } catch (RuntimeException e) {
            lease.close();
            throw e;
        }
    }

    public void cancelMktData(int reqId) {
        send(RequestType.CANCEL_MARKET_DATA, null, Encoder.cancelMktData(reqId));
        releaseMarketData(reqId);
    }

    public void reqHistoricalData(int reqId, Contract contract, String endDateTime, String barSizeSetting,
                                  String duration, boolean useRTH, String whatToShow, int formatDate,
                                  boolean keepUpToDate) {
        String fingerprint = "%s|%s|%s|%s|%b|%s|%d".formatted(
                fingerprint(contract), endDateTime, barSizeSetting, duration, useRTH, whatToShow, formatDate);
        send(RequestType.HISTORICAL_DATA, fingerprint, Encoder.reqHistoricalData(
                reqId, contract, endDateTime, barSizeSetting, duration, useRTH, whatToShow, formatDate, keepUpToDate));
    }

    public void cancelHistoricalData(int reqId) {
        send(RequestType.CANCEL_HISTORICAL_DATA, null, Encoder.cancelHistoricalData(reqId));
    }

    public void placeOrder(int orderId, Contract contract, Order order) {
        send(RequestType.PLACE_ORDER, null, Encoder.placeOrder(orderId, contract, order));
    }

    public void cancelOrder(int orderId) {
        send(RequestType.CANCEL_ORDER, null, Encoder.cancelOrder(orderId));
    }

    public void reqOpenOrders() {
        send(RequestType.OPEN_ORDERS, null, Encoder.reqOpenOrders());
    }

    public void reqPositions() {
        send(RequestType.POSITIONS, null, Encoder.reqPositions());
    }

    public void cancelPositions() {
        send(RequestType.CANCEL_POSITIONS, null, Encoder.cancelPositions());
    }

    public void reqExecutions(int reqId, ExecutionFilter filter) {
        send(RequestType.EXECUTIONS, filter == null ? null : filter.accountCode(),
                Encoder.reqExecutions(reqId, filter == null ? ExecutionFilter.all() : filter));
    }

    public void reqAccountUpdates(boolean subscribe, String accountCode) {
        send(RequestType.ACCOUNT_UPDATES, accountCode, Encoder.reqAccountUpdates(subscribe, accountCode));
    }

    public void setMarketDataType(int marketDataType) {
        send(RequestType.MARKET_DATA_TYPE, null, Encoder.setMarketDataType(marketDataType));
    }

    // ------------------------------------------------------------------ internals

    private void send(RequestType type, String fingerprint, byte[] frame) {
        Transport t = transport;
        if (t == null || !t.isOpen()) {
            throw new IllegalStateException("not connected");
        }
        pacer.acquire(type, fingerprint);
        t.send(frame);
    }

    private void releaseMarketData(int reqId) {
        Pacer.Lease lease = marketDataLeases.remove(reqId);
        if (lease != null) {
            lease.close();
        }
    }

    private void releaseAllMarketData() {
        marketDataLeases.values().forEach(Pacer.Lease::close);
        marketDataLeases.clear();
    }

    /** Intercepts lifecycle events so held permits are always returned. */
    private void dispatch(IbEvent event) {
        switch (event) {
            case IbEvent.Tick.SnapshotEnd(var reqId) -> releaseMarketData(reqId);
            case IbEvent.Disconnected ignored -> releaseAllMarketData();
            default -> { }
        }
        handler.onEvent(event);
    }

    private static String fingerprint(Contract contract) {
        return contract == null ? "" : "%s|%s|%s|%s|%s|%s".formatted(
                contract.conId(), contract.symbol(), contract.secType(),
                contract.exchange(), contract.currency(), contract.lastTradeDateOrContractMonth());
    }
}
