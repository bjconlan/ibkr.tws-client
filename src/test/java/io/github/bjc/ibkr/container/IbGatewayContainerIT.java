package io.github.bjc.ibkr.container;

import io.github.bjc.ibkr.TwsClient;
import io.github.bjc.ibkr.TwsConfig;
import io.github.bjc.ibkr.event.IbEvent;
import io.github.bjc.ibkr.model.Contract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts a paper IB Gateway with Testcontainers and drives the client against it. Run with
 * {@code mvn verify}; skipped unless credentials are available (see {@code .env.example}) and
 * Docker is running.
 *
 * <p>Container tests live in {@code *IT} so the fast unit suite in {@code mvn test} does not
 * pull an image or log into IBKR.
 */
@Tag("container")
class IbGatewayContainerIT {

    private static IbGatewayContainer gateway;

    @BeforeAll
    static void startGateway() {
        Map<String, String> credentials = DotEnv.credentials();
        String userId = credentials.get("TWS_USERID");
        String password = credentials.get("TWS_PASSWORD");

        Assumptions.assumeTrue(userId != null && !userId.isBlank() && password != null && !password.isBlank(),
                "TWS_USERID/TWS_PASSWORD not configured; copy .env.example to .env to run this test");
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available; skipping IB Gateway container test");

        gateway = new IbGatewayContainer(userId, password);
        gateway.start();
    }

    @AfterAll
    static void stopGateway() {
        if (gateway != null) {
            gateway.stop();
        }
    }

    @Test
    void connectsAndServesSessionContractAndTimeData() throws Exception {
        BlockingQueue<IbEvent> events = new LinkedBlockingQueue<>();
        TwsConfig config = new TwsConfig(gateway.gatewayHost(), gateway.gatewayPort(), 91, "",
                Duration.ofSeconds(15), 3, Duration.ofSeconds(2));

        try (TwsClient client = new TwsClient(config, events::add)) {
            client.connect();
            assertTrue(client.isConnected());
            assertEquals(226, client.serverVersion());

            client.reqCurrentTime();
            client.reqContractDetails(1, Contract.stock("AAPL"));

            Map<Class<? extends IbEvent>, IbEvent> found = awaitAll(events, Duration.ofSeconds(30),
                    IbEvent.ManagedAccounts.class,
                    IbEvent.CurrentTime.class,
                    IbEvent.ContractDetailsReceived.class,
                    IbEvent.ContractDetailsEnd.class);

            @SuppressWarnings("unchecked")
            var accounts = (IbEvent.ManagedAccounts) found.get(IbEvent.ManagedAccounts.class);
            assertFalse(accounts.accounts().isEmpty());

            var currentTime = (IbEvent.CurrentTime) found.get(IbEvent.CurrentTime.class);
            assertTrue(currentTime.epochSeconds() > 0);

            var details = (IbEvent.ContractDetailsReceived) found.get(IbEvent.ContractDetailsReceived.class);
            assertNotNull(details.details().contract());
            assertEquals("AAPL", details.details().contract().symbol());
        }
    }

    /** Collects matching events until every wanted type has been seen, or the timeout elapses. */
    @SafeVarargs
    private static Map<Class<? extends IbEvent>, IbEvent> awaitAll(BlockingQueue<IbEvent> events,
                                                                   Duration timeout,
                                                                   Class<? extends IbEvent>... wanted)
            throws InterruptedException {
        List<Class<? extends IbEvent>> remaining = new java.util.ArrayList<>(List.of(wanted));
        Map<Class<? extends IbEvent>, IbEvent> found = new HashMap<>();
        long deadline = System.nanoTime() + timeout.toNanos();

        while (!remaining.isEmpty() && System.nanoTime() < deadline) {
            IbEvent event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (event == null) {
                break;
            }
            for (Class<? extends IbEvent> type : List.copyOf(remaining)) {
                if (type.isInstance(event)) {
                    found.put(type, event);
                    remaining.remove(type);
                }
            }
        }
        assertTrue(remaining.isEmpty(), "timed out waiting for " + remaining);
        return found;
    }
}
