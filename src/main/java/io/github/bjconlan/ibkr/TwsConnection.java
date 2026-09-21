package io.github.bjconlan.ibkr;

import com.google.protobuf.MessageLite;
import io.github.bjconlan.ibkr.event.IbEvent;
import io.github.bjconlan.ibkr.pacing.Pacer;
import io.github.bjconlan.ibkr.pacing.PacingKeys;
import io.github.bjconlan.ibkr.protocol.Encoder;
import io.github.bjconlan.ibkr.protocol.IncomingId;
import io.github.bjconlan.ibkr.protocol.OutgoingId;
import io.github.bjconlan.ibkr.proto.AccountDataRequestProto;
import io.github.bjconlan.ibkr.proto.CancelContractDataProto;
import io.github.bjconlan.ibkr.proto.CancelHistoricalDataProto;
import io.github.bjconlan.ibkr.proto.CancelMarketDataProto;
import io.github.bjconlan.ibkr.proto.CancelOrderRequestProto;
import io.github.bjconlan.ibkr.proto.CancelPositionsProto;
import io.github.bjconlan.ibkr.proto.ContractDataRequestProto;
import io.github.bjconlan.ibkr.proto.ContractProto;
import io.github.bjconlan.ibkr.proto.CurrentTimeRequestProto;
import io.github.bjconlan.ibkr.proto.ExecutionFilterProto;
import io.github.bjconlan.ibkr.proto.ExecutionRequestProto;
import io.github.bjconlan.ibkr.proto.HistoricalDataRequestProto;
import io.github.bjconlan.ibkr.proto.IdsRequestProto;
import io.github.bjconlan.ibkr.proto.MarketDataRequestProto;
import io.github.bjconlan.ibkr.proto.MarketDataTypeRequestProto;
import io.github.bjconlan.ibkr.proto.OpenOrdersRequestProto;
import io.github.bjconlan.ibkr.proto.OrderProto;
import io.github.bjconlan.ibkr.proto.PlaceOrderRequestProto;
import io.github.bjconlan.ibkr.proto.PositionsRequestProto;
import io.github.bjconlan.ibkr.proto.StartApiRequestProto;
import io.github.bjconlan.ibkr.proto.TickSnapshotEndProto;
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
import java.util.function.Consumer;

/**
 * One TWS API connection: a client id, a socket, its own request-id sequence and its own pacing.
 * Obtained from {@link #open(TwsConfig, EventHandler)} and closed by the caller. Request scopes are
 * created with {@link TwsSession}.
 *
 * <p>{@link #send(OutgoingId, MessageLite)} frames and paces any request the protocol defines;
 * the typed methods below are conveniences that build the common messages, and the request-id
 * taking ones are deprecated in favour of {@link TwsSession}. Request methods are synchronous:
 * they may block on the {@link Pacer} until it is safe to send (virtual threads make this cheap),
 * then hand the frame to the transport. Server messages arrive as {@link IbEvent}s on the
 * {@link EventHandler} passed to the constructor, in order; responses to a {@link TwsSession}
 * request go to that request instead.
 *
 * <p>Pacing is configured from {@link TwsConfig#marketDataLines()}: the account's market data line
 * entitlement sets the aggregate request rate and the subscription concurrency limits. Pass a
 * custom {@link Pacer} to the constructor to override any of the documented rules.
 *
 * <p>Market data lines are tracked as held permits: one is taken per subscription and returned
 * when the subscription is cancelled, when a snapshot completes, or when the connection drops.
 */
public final class TwsConnection implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(TwsConnection.class.getName());

    private final TwsConfig config;
    private final EventHandler handler;
    private final Pacer pacer;

    private final AtomicInteger reqIdSeq = new AtomicInteger(1);
    private final Map<Integer, Pacer.Lease> marketDataLeases = new ConcurrentHashMap<>();
    private final Map<Integer, RequestHandle> pending = new ConcurrentHashMap<>();
    private final Map<IncomingId, TwsRequest> routes = new ConcurrentHashMap<>();

    private volatile Transport transport;

    private TwsConnection(TwsConfig config, EventHandler handler, Pacer pacer) {
        this.config = config;
        this.handler = handler;
        this.pacer = pacer;
    }

    /**
     * Opens a connection, retrying transient failures with exponential backoff, then starts the
     * API so TWS begins delivering {@code ManagedAccounts} and {@code NextValidId}. The caller owns
     * the returned connection and must close it.
     *
     * @throws IOException if the socket cannot be opened or the handshake fails
     */
    public static TwsConnection open(TwsConfig config, EventHandler handler) throws IOException {
        return open(config, handler, Pacer.of(config.marketDataLines()));
    }

    /** As {@link #open(TwsConfig, EventHandler)} with an explicit {@link Pacer}. */
    public static TwsConnection open(TwsConfig config, EventHandler handler, Pacer pacer) throws IOException {
        TwsConnection connection = new TwsConnection(config, handler, pacer);
        try {
            connection.connect();
            LOG.log(System.Logger.Level.DEBUG, "opened %s:%d as client %d (server %d)".formatted(
                    config.host(), config.port(), config.clientId(), connection.serverVersion()));
            return connection;
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "failed to open %s:%d as client %d".formatted(
                    config.host(), config.port(), config.clientId()), e);
            connection.close();
            throw e;
        }
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
     * Creates a new request scope over this connection. The session allocates request ids through
     * this connection and cancels its own requests when closed; the connection stays open. Each
     * call returns an independent scope.
     */
    public TwsSession createSession() {
        return new TwsSession(() -> this);
    }

    /**
     * Connects, retrying transient failures with exponential backoff, then starts the API so
     * TWS begins delivering {@code ManagedAccounts} and {@code NextValidId}.
     */
    private void connect() throws IOException {
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
            send(OutgoingId.START_API, PacingKeys.NONE, startApi());
            MarketDataType marketDataType = config.marketDataType();
            if (marketDataType != null) {
                setMarketDataType(marketDataType);
            }
        } catch (RuntimeException e) {
            throw new IOException("could not connect to %s:%d".formatted(config.host(), config.port()), e);
        }
    }

    @Override
    public void close() {
        Transport t = transport;
        transport = null;
        if (t != null) {
            LOG.log(System.Logger.Level.DEBUG, "closing %s:%d as client %d".formatted(
                    config.host(), config.port(), config.clientId()));
            t.close();
        }
        releaseAllMarketData();
        failAllPending(new IllegalStateException("connection closed"));
    }

    /** The next unused request id. */
    @Deprecated(since = "1.0.0", forRemoval = true) // use TwsSession instead
    public int nextReqId() {
        return reqIdSeq.getAndIncrement();
    }

    // ------------------------------------------------------------------ generic request

    /**
     * Frames and sends any request, applying the aggregate limit and the type-specific rules for
     * {@code id} if any are defined. This is the parity entry point: every {@link OutgoingId} can
     * be sent with its generated protobuf message.
     */
    public void send(OutgoingId id, MessageLite body) {
        send(id, PacingKeys.NONE, body);
    }

    // ------------------------------------------------------------------ typed conveniences

    public void reqCurrentTime() {
        send(OutgoingId.REQ_CURRENT_TIME, PacingKeys.NONE,
                CurrentTimeRequestProto.CurrentTimeRequest.getDefaultInstance());
    }

    public void reqIds() {
        send(OutgoingId.REQ_IDS, PacingKeys.NONE,
                IdsRequestProto.IdsRequest.newBuilder().setNumIds(1).build());
    }

    @Deprecated(since = "1.0.0", forRemoval = true) // use TwsSession instead
    public void reqContractDetails(int reqId, ContractProto.Contract contract) {
        send(OutgoingId.REQ_CONTRACT_DATA, PacingKeys.NONE,
                ContractDataRequestProto.ContractDataRequest.newBuilder()
                        .setReqId(reqId).setContract(contract).build());
    }

    @Deprecated(since = "1.0.0", forRemoval = true) // use TwsSession instead
    public void cancelContractDetails(int reqId) {
        send(OutgoingId.CANCEL_CONTRACT_DATA, PacingKeys.NONE,
                CancelContractDataProto.CancelContractData.newBuilder().setReqId(reqId).build());
    }

    @Deprecated(since = "1.0.0", forRemoval = true) // use TwsSession instead
    public void reqMktData(int reqId, ContractProto.Contract contract, String genericTickList,
                           boolean snapshot, boolean regulatorySnapshot) {
        MarketDataRequestProto.MarketDataRequest.Builder b = MarketDataRequestProto.MarketDataRequest.newBuilder()
                .setReqId(reqId).setContract(contract);
        if (genericTickList != null && !genericTickList.isEmpty()) {
            b.setGenericTickList(genericTickList);
        }
        if (snapshot) {
            b.setSnapshot(true);
        }
        if (regulatorySnapshot) {
            b.setRegulatorySnapshot(true);
        }

        Pacer.Lease lease = pacer.acquireLease(OutgoingId.REQ_MKT_DATA);
        try {
            send(OutgoingId.REQ_MKT_DATA, PacingKeys.NONE, b.build());
            marketDataLeases.put(reqId, lease);
        } catch (RuntimeException e) {
            lease.close();
            throw e;
        }
    }

    @Deprecated(since = "1.0.0", forRemoval = true) // use TwsSession instead
    public void cancelMktData(int reqId) {
        send(OutgoingId.CANCEL_MKT_DATA, PacingKeys.NONE,
                CancelMarketDataProto.CancelMarketData.newBuilder().setReqId(reqId).build());
        releaseMarketData(reqId);
    }

    /**
     * Requests historical bars. Parameter order mirrors the upstream {@code EClient}:
     * {@code endDateTime, duration, barSizeSetting, whatToShow, useRTH, formatDate, keepUpToDate}.
     */
    @Deprecated(since = "1.0.0", forRemoval = true) // use TwsSession instead
    public void reqHistoricalData(int reqId, ContractProto.Contract contract, String endDateTime,
                                  String duration, String barSizeSetting, String whatToShow,
                                  boolean useRTH, int formatDate, boolean keepUpToDate) {
        String contractScope = "%s|%s".formatted(fingerprint(contract), whatToShow);
        String request = "%s|%s|%s|%s|%b|%d".formatted(
                contractScope, endDateTime, barSizeSetting, duration, useRTH, formatDate);
        send(OutgoingId.REQ_HISTORICAL_DATA, new PacingKeys(request, contractScope),
                HistoricalDataRequestProto.HistoricalDataRequest.newBuilder()
                        .setReqId(reqId)
                        .setContract(contract)
                        .setEndDateTime(endDateTime == null ? "" : endDateTime)
                        .setBarSizeSetting(barSizeSetting)
                        .setDuration(duration)
                        .setUseRTH(useRTH)
                        .setWhatToShow(whatToShow)
                        .setFormatDate(formatDate)
                        .setKeepUpToDate(keepUpToDate)
                        .build());
    }

    @Deprecated(since = "1.0.0", forRemoval = true) // use TwsSession instead
    public void cancelHistoricalData(int reqId) {
        send(OutgoingId.CANCEL_HISTORICAL_DATA, PacingKeys.NONE,
                CancelHistoricalDataProto.CancelHistoricalData.newBuilder().setReqId(reqId).build());
    }

    public void placeOrder(int orderId, ContractProto.Contract contract, OrderProto.Order order) {
        send(OutgoingId.PLACE_ORDER, PacingKeys.NONE,
                PlaceOrderRequestProto.PlaceOrderRequest.newBuilder()
                        .setOrderId(orderId).setContract(contract).setOrder(order).build());
    }

    public void cancelOrder(int orderId) {
        send(OutgoingId.CANCEL_ORDER, PacingKeys.NONE,
                CancelOrderRequestProto.CancelOrderRequest.newBuilder().setOrderId(orderId).build());
    }

    public void reqOpenOrders() {
        send(OutgoingId.REQ_OPEN_ORDERS, PacingKeys.NONE,
                OpenOrdersRequestProto.OpenOrdersRequest.getDefaultInstance());
    }

    public void reqPositions() {
        send(OutgoingId.REQ_POSITIONS, PacingKeys.NONE,
                PositionsRequestProto.PositionsRequest.getDefaultInstance());
    }

    public void cancelPositions() {
        send(OutgoingId.CANCEL_POSITIONS, PacingKeys.NONE,
                CancelPositionsProto.CancelPositions.getDefaultInstance());
    }

    @Deprecated(since = "1.0.0", forRemoval = true) // use TwsSession instead
    public void reqExecutions(int reqId, ExecutionFilterProto.ExecutionFilter filter) {
        send(OutgoingId.REQ_EXECUTIONS, PacingKeys.NONE,
                ExecutionRequestProto.ExecutionRequest.newBuilder()
                        .setReqId(reqId)
                        .setExecutionFilter(filter == null
                                ? ExecutionFilterProto.ExecutionFilter.getDefaultInstance()
                                : filter)
                        .build());
    }

    public void reqAccountUpdates(boolean subscribe, String accountCode) {
        AccountDataRequestProto.AccountDataRequest.Builder b = AccountDataRequestProto.AccountDataRequest.newBuilder()
                .setSubscribe(subscribe);
        if (accountCode != null && !accountCode.isEmpty()) {
            b.setAcctCode(accountCode);
        }
        send(OutgoingId.REQ_ACCOUNT_DATA, PacingKeys.NONE, b.build());
    }

    public void setMarketDataType(MarketDataType marketDataType) {
        send(OutgoingId.REQ_MARKET_DATA_TYPE, PacingKeys.NONE,
                MarketDataTypeRequestProto.MarketDataTypeRequest.newBuilder()
                        .setMarketDataType(marketDataType.code()).build());
    }

    // ------------------------------------------------------------------ internals

    private StartApiRequestProto.StartApiRequest startApi() {
        StartApiRequestProto.StartApiRequest.Builder b = StartApiRequestProto.StartApiRequest.newBuilder()
                .setClientId(config.clientId());
        String capabilities = config.optionalCapabilities();
        if (capabilities != null && !capabilities.isEmpty()) {
            b.setOptionalCapabilities(capabilities);
        }
        return b.build();
    }

    private void send(OutgoingId id, PacingKeys keys, MessageLite body) {
        Transport t = transport;
        if (t == null || !t.isOpen()) {
            throw new IllegalStateException("not connected");
        }
        pacer.acquire(id, keys);
        LOG.log(System.Logger.Level.TRACE, "send %s (client %d)".formatted(id, config.clientId()));
        t.send(Encoder.encode(id, body));
    }

    // ------------------------------------------------------------------ request routing

    /** Creates (but does not send) a request handle; the session registers it before {@link #start}. */
    RequestHandle create(TwsRequest request, RequestSink sink, Consumer<RequestHandle> onDone) {
        return new RequestHandle(this, reqIdSeq.getAndIncrement(), request, sink, onDone);
    }

    /** Paces and sends a created handle, then tracks it until it terminates, fails or is closed. */
    void start(RequestHandle handle) {
        Transport t = transport;
        if (t == null || !t.isOpen()) {
            throw new IllegalStateException("not connected");
        }
        TwsRequest request = handle.request();
        request.responses().forEach(id -> routes.put(id, request));
        try {
            if (request.lineLease()) {
                handle.lease(pacer.acquireLease(request.id()));
            }
            pacer.acquire(request.id(), request.pacing());
            pending.put(handle.reqId(), handle);
            t.send(Encoder.encode(request.id(), request.encode().apply(handle.reqId())));
        } catch (RuntimeException e) {
            handle.fail(e);
            throw e;
        }
    }

    void forget(int reqId) {
        pending.remove(reqId);
    }

    void sendIfOpen(OutgoingId id, MessageLite body) {
        Transport t = transport;
        if (t != null && t.isOpen()) {
            t.send(Encoder.encode(id, body));
        }
    }

    private void failAllPending(Throwable cause) {
        for (RequestHandle handle : pending.values()) {
            handle.fail(cause);
        }
        pending.clear();
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

    /** Routes events to the request waiting for them, then to the connection-level handler. */
    private void dispatch(IbEvent event) {
        switch (event) {
            case IbEvent.Message m -> {
                if (route(m)) {
                    return;
                }
                if (m.payload() instanceof TickSnapshotEndProto.TickSnapshotEnd end) {
                    releaseMarketData(end.getReqId());
                }
            }
            case IbEvent.Disconnected d -> {
                LOG.log(System.Logger.Level.DEBUG, "client %d disconnected: %s"
                        .formatted(config.clientId(), d.reason()));
                failAllPending(d.cause() == null ? new IllegalStateException(d.reason()) : d.cause());
                releaseAllMarketData();
            }
            case IbEvent.Error e -> {
                LOG.log(System.Logger.Level.DEBUG, "error %d for request %d: %s"
                        .formatted(e.code(), e.requestId(), e.message()));
                RequestHandle handle = pending.get(e.requestId());
                if (handle != null) {
                    handle.fail(new IbRequestException(e));
                }
            }
            case IbEvent.Connected ignored -> { }
        }
        handler.onEvent(event);
    }

    /** @return true if the message belonged to a pending request and was delivered to it. */
    private boolean route(IbEvent.Message m) {
        TwsRequest request = routes.get(IncomingId.fromId(m.id()));
        if (request == null) {
            return false;
        }
        RequestHandle handle = pending.get(request.correlate().applyAsInt(m));
        if (handle == null) {
            return false;
        }
        handle.message(m);
        if (request.terminal() != null && request.terminal().test(m)) {
            handle.terminal();
        }
        return true;
    }

    private static String fingerprint(ContractProto.Contract contract) {
        if (contract == null) {
            return "";
        }
        return "%s|%s|%s|%s|%s|%s".formatted(
                contract.getConId(), contract.getSymbol(), contract.getSecType(),
                contract.getExchange(), contract.getCurrency(), contract.getLastTradeDateOrContractMonth());
    }
}
