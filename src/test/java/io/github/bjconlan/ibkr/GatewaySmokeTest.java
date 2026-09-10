package io.github.bjconlan.ibkr;

import io.github.bjconlan.ibkr.event.IbEvent;
import io.github.bjconlan.ibkr.proto.ContractDataEndProto;
import io.github.bjconlan.ibkr.proto.ContractDataProto;
import io.github.bjconlan.ibkr.proto.ContractProto;
import io.github.bjconlan.ibkr.proto.CurrentTimeProto;
import io.github.bjconlan.ibkr.proto.ErrorMessageProto;
import io.github.bjconlan.ibkr.proto.TickPriceProto;
import io.github.bjconlan.ibkr.proto.TickSizeProto;
import io.github.bjconlan.ibkr.proto.TickSnapshotEndProto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in smoke test against a real IB Gateway / TWS. Disabled unless {@code IBKR_SMOKE=true} so
 * the normal build never depends on a running gateway.
 *
 * <pre>{@code
 * IBKR_SMOKE=true IBKR_GATEWAY_PORT=4002 mvn test -Dtest=GatewaySmokeTest
 * }</pre>
 */
@EnabledIfEnvironmentVariable(named = "IBKR_SMOKE", matches = "true")
class GatewaySmokeTest {

    private static String host() {
        return System.getenv().getOrDefault("IBKR_GATEWAY_HOST", "127.0.0.1");
    }

    private static int port() {
        return Integer.parseInt(System.getenv().getOrDefault("IBKR_GATEWAY_PORT", "4002"));
    }

    private static int clientId() {
        return Integer.parseInt(System.getenv().getOrDefault("IBKR_CLIENT_ID", "99"));
    }

    @Test
    void connectsAndRetrievesContractDetailsAndTime() throws Exception {
        BlockingQueue<IbEvent> events = new LinkedBlockingQueue<>();
        TwsConfig config = new TwsConfig(host(), port(), clientId(), "", Duration.ofSeconds(10), 2,
                Duration.ofMillis(500));

        try (TwsClient client = new TwsClient(config, events::add)) {
            client.connect();
            assertTrue(client.isConnected());
            System.out.println("connected to gateway, server version " + client.serverVersion());

            client.reqCurrentTime();
            client.reqContractDetails(1, contract("AAPL"));

            CurrentTimeProto.CurrentTime currentTime = await(events, CurrentTimeProto.CurrentTime.class);
            assertTrue(currentTime.getCurrentTime() > 0);

            ContractDataProto.ContractData data = await(events, ContractDataProto.ContractData.class);
            ContractProto.Contract contract = data.getContract();
            System.out.printf("resolved: %s %s (%s) via %s%n",
                    contract.getSymbol(), contract.getSecType(),
                    data.getContractDetails().getLongName(), contract.getExchange());
            assertEquals("AAPL", contract.getSymbol());

            await(events, ContractDataEndProto.ContractDataEnd.class);

            // Market data is account-entitlement dependent; log it but do not fail if denied.
            client.setMarketDataType(3); // delayed
            client.reqMktData(2, contract("AAPL"), "", true, false);
            IbEvent tick = awaitAny(events, Duration.ofSeconds(10),
                    TickPriceProto.TickPrice.class, TickSizeProto.TickSize.class,
                    TickSnapshotEndProto.TickSnapshotEnd.class, ErrorMessageProto.ErrorMessage.class);
            System.out.println("market data result: " + describe(tick));
        }
    }

    private static <T extends com.google.protobuf.Message> T await(BlockingQueue<IbEvent> events,
                                                                   Class<T> payloadType) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            IbEvent event = events.poll(30, TimeUnit.SECONDS);
            if (event instanceof IbEvent.Message m && payloadType.isInstance(m.payload())) {
                return payloadType.cast(m.payload());
            }
            if (event != null) {
                System.out.println("(ignoring) " + describe(event));
            }
        }
        throw new AssertionError("did not receive " + payloadType.getSimpleName());
    }

    @SafeVarargs
    private static IbEvent awaitAny(BlockingQueue<IbEvent> events, Duration timeout,
                                    Class<? extends com.google.protobuf.Message>... payloadTypes) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            IbEvent event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (event == null) {
                break;
            }
            if (event instanceof IbEvent.Message m) {
                for (Class<? extends com.google.protobuf.Message> type : payloadTypes) {
                    if (type.isInstance(m.payload())) {
                        return event;
                    }
                }
            }
            System.out.println("(ignoring) " + describe(event));
        }
        throw new AssertionError("did not receive any of " + java.util.Arrays.toString(payloadTypes));
    }

    private static ContractProto.Contract contract(String symbol) {
        return ContractProto.Contract.newBuilder()
                .setSymbol(symbol).setSecType("STK").setExchange("SMART").setCurrency("USD")
                .build();
    }

    private static String describe(IbEvent event) {
        return switch (event) {
            case IbEvent.Error e -> "Error[%d] %d: %s".formatted(e.requestId(), e.code(), e.message());
            case IbEvent.Message m when m.payload() instanceof ErrorMessageProto.ErrorMessage e ->
                    "Error[%d] %d: %s".formatted(e.getId(), e.getErrorCode(), e.getErrorMsg());
            case IbEvent.Message m when m.payload() instanceof TickPriceProto.TickPrice p ->
                    "Price[%d] %.4f".formatted(p.getReqId(), p.getPrice());
            case IbEvent.Message m when m.payload() instanceof TickSizeProto.TickSize s ->
                    "Size[%d] %s".formatted(s.getReqId(), s.getSize());
            case IbEvent.Message m when m.payload() instanceof TickSnapshotEndProto.TickSnapshotEnd s ->
                    "SnapshotEnd[%d]".formatted(s.getReqId());
            default -> event.toString();
        };
    }
}
