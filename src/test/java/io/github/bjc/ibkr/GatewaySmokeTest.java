package io.github.bjc.ibkr;

import io.github.bjc.ibkr.event.IbEvent;
import io.github.bjc.ibkr.model.Contract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            client.reqContractDetails(1, Contract.stock("AAPL"));

            IbEvent.CurrentTime currentTime = await(events, IbEvent.CurrentTime.class);
            assertTrue(currentTime.epochSeconds() > 0);

            IbEvent.ContractDetailsReceived details = await(events, IbEvent.ContractDetailsReceived.class);
            assertNotNull(details.details().contract());
            System.out.printf("resolved: %s %s (%s) via %s, minTick=%s%n",
                    details.details().contract().symbol(),
                    details.details().contract().secType(),
                    details.details().longName(),
                    details.details().contract().exchange(),
                    details.details().minTick());
            assertEquals("AAPL", details.details().contract().symbol());

            assertNotNull(await(events, IbEvent.ContractDetailsEnd.class));

            // Market data is account-entitlement dependent; log it but do not fail if denied.
            client.setMarketDataType(3); // delayed
            client.reqMktData(2, Contract.stock("AAPL"), "", true, false);
            IbEvent tick = awaitAny(events, Duration.ofSeconds(10),
                    IbEvent.Tick.Price.class, IbEvent.Tick.Size.class, IbEvent.Tick.SnapshotEnd.class,
                    IbEvent.Error.class);
            System.out.println("market data result: " + describe(tick));
        }
    }

    private static <T extends IbEvent> T await(BlockingQueue<IbEvent> events, Class<T> type) throws InterruptedException {
        return type.cast(awaitAny(events, Duration.ofSeconds(30), type));
    }

    @SafeVarargs
    private static IbEvent awaitAny(BlockingQueue<IbEvent> events, Duration timeout,
                                    Class<? extends IbEvent>... types) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            IbEvent event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (event == null) {
                break;
            }
            for (Class<? extends IbEvent> type : types) {
                if (type.isInstance(event)) {
                    return event;
                }
            }
            System.out.println("(ignoring) " + describe(event));
        }
        throw new AssertionError("did not receive any of " + java.util.Arrays.toString(types));
    }

    private static String describe(IbEvent event) {
        return switch (event) {
            case IbEvent.Error e -> "Error[%d] %d: %s".formatted(e.requestId(), e.code(), e.message());
            case IbEvent.Tick.Price p -> "Price[%d] %.4f".formatted(p.requestId(), p.price());
            case IbEvent.Tick.Size s -> "Size[%d] %s".formatted(s.requestId(), s.size());
            case IbEvent.Tick.SnapshotEnd s -> "SnapshotEnd[%d]".formatted(s.requestId());
            default -> event.toString();
        };
    }
}
