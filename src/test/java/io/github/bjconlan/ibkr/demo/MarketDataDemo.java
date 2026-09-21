package io.github.bjconlan.ibkr.demo;

import io.github.bjconlan.ibkr.MarketDataType;
import io.github.bjconlan.ibkr.TwsClientFactory;
import io.github.bjconlan.ibkr.TwsConfig;
import io.github.bjconlan.ibkr.TwsRequest;
import io.github.bjconlan.ibkr.TwsSession;
import io.github.bjconlan.ibkr.event.IbEvent;
import io.github.bjconlan.ibkr.proto.ContractProto;
import io.github.bjconlan.ibkr.proto.CurrentTimeProto;
import io.github.bjconlan.ibkr.proto.ErrorMessageProto;
import io.github.bjconlan.ibkr.proto.HistoricalDataEndProto;
import io.github.bjconlan.ibkr.proto.HistoricalDataProto;
import io.github.bjconlan.ibkr.proto.ManagedAccountsProto;
import io.github.bjconlan.ibkr.proto.NextValidIdProto;
import io.github.bjconlan.ibkr.proto.TickPriceProto;
import io.github.bjconlan.ibkr.proto.TickSizeProto;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Minimal console demo: connect, stream AAPL market data, fetch one day of 5-minute bars, print
 * everything that comes back.
 *
 * <pre>{@code
 * # TWS paper-trading default (127.0.0.1:7497)
 * mvn -q compile exec:java
 *
 * # or an explicit host, port and client id
 * mvn -q compile exec:java -Dexec.args="127.0.0.1 4002 11"
 * }</pre>
 *
 * <p>The connection handler is an ordinary method reference and the callback logic is a
 * pattern-matching switch over the sealed {@link IbEvent} hierarchy, narrowing each message to its
 * generated protobuf payload. Requests are issued through a {@link TwsSession}, so no request id
 * is allocated by hand and no {@code Thread.sleep} is needed: the stream is consumed by a latch 
 * or a blocking {@code join}, and closing the scope cancels it.
 */
public final class MarketDataDemo {

    private MarketDataDemo() {
    }

    public static void main(String[] args) throws Exception {
        String host = arg(args, 0, System.getenv().getOrDefault("IBKR_GATEWAY_HOST", "127.0.0.1"));
        int port = Integer.parseInt(arg(args, 1, System.getenv().getOrDefault("IBKR_GATEWAY_PORT", "7497")));
        int clientId = Integer.parseInt(arg(args, 2, "1"));

        TwsConfig config = TwsConfig.defaults(clientId).withHost(host).withPort(port)
                .withMarketDataType(MarketDataType.DELAYED);   // paper accounts lack live entitlements
        List<Integer> clientIds = List.of(clientId, clientId + 1);
        System.out.printf("connecting to %s:%d as clients %s%n", host, port, clientIds);

        ContractProto.Contract aapl = ContractProto.Contract.newBuilder()
                .setSymbol("AAPL").setSecType("STK").setExchange("SMART").setCurrency("USD").build();

        try (TwsClientFactory factory = new TwsClientFactory(config, MarketDataDemo::render, clientIds);
             TwsSession session = factory.createSession()) {
            // Push: the handler runs on the dispatcher; wait for ten ticks, not for a fixed time.
            CountDownLatch ticks = new CountDownLatch(10);
            try (AutoCloseable subscription = session.on(
                    TwsRequest.marketData(aapl, "", false, false),
                    message -> {
                        renderMessage(message);
                        ticks.countDown();
                    })) {
                if (!ticks.await(30, TimeUnit.SECONDS)) {
                    System.err.println("timed out waiting for ticks");
                }
            }   // closing cancels the subscription and releases the market data line

            // Bounded: blocks until the terminal message, then prints every bar.
            session.submit(TwsRequest.historicalData(aapl, "", "1 D", "5 mins", "TRADES", true, 1, false))
                    .join()
                    .forEach(MarketDataDemo::renderMessage);
        }
    }

    private static String arg(String[] args, int index, String fallback) {
        return args.length > index && !args[index].isBlank() ? args[index] : fallback;
    }

    private static void render(IbEvent event) {
        switch (event) {
            case IbEvent.Connected c ->
                    System.out.printf("connected: server=%d time=%s%n", c.serverVersion(), c.twsTime());
            case IbEvent.Disconnected d ->
                    System.out.println("disconnected: " + d.reason());
            case IbEvent.Error e ->
                    System.err.printf("[%d] error %d: %s%n", e.requestId(), e.code(), e.message());
            case IbEvent.Message m -> renderMessage(m);
        }
    }

    private static void renderMessage(IbEvent.Message m) {
        switch (m.payload()) {
            case ManagedAccountsProto.ManagedAccounts a ->
                    System.out.println("accounts: " + a.getAccountsList());
            case NextValidIdProto.NextValidId n ->
                    System.out.println("next valid order id: " + n.getOrderId());
            case CurrentTimeProto.CurrentTime t ->
                    System.out.println("server time: " + t.getCurrentTime());
            case TickPriceProto.TickPrice p ->
                    System.out.printf("[%d] price tickType=%d %.4f%n", p.getReqId(), p.getTickType(), p.getPrice());
            case TickSizeProto.TickSize s ->
                    System.out.printf("[%d] size tickType=%d %s%n", s.getReqId(), s.getTickType(), s.getSize());
            case HistoricalDataProto.HistoricalData h -> h.getHistoricalDataBarsList().forEach(bar ->
                    System.out.printf("[%d] %s O=%.2f H=%.2f L=%.2f C=%.2f V=%s%n",
                            h.getReqId(), bar.getDate(), bar.getOpen(), bar.getHigh(),
                            bar.getLow(), bar.getClose(), bar.getVolume()));
            case HistoricalDataEndProto.HistoricalDataEnd e ->
                    System.out.printf("[%d] history done %s .. %s%n", e.getReqId(), e.getStartDateStr(), e.getEndDateStr());
            case ErrorMessageProto.ErrorMessage e ->
                    System.err.printf("[%d] error %d: %s%n", e.getId(), e.getErrorCode(), e.getErrorMsg());
            default -> {
                // Other messages are not interesting to this demo.
            }
        }
    }
}
