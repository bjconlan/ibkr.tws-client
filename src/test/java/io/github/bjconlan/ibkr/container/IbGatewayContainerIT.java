package io.github.bjconlan.ibkr.container;

import io.github.bjconlan.ibkr.TwsClient;
import io.github.bjconlan.ibkr.TwsConfig;
import io.github.bjconlan.ibkr.event.IbEvent;
import io.github.bjconlan.ibkr.proto.ContractDataEndProto;
import io.github.bjconlan.ibkr.proto.ContractDataProto;
import io.github.bjconlan.ibkr.proto.ContractProto;
import io.github.bjconlan.ibkr.proto.CurrentTimeProto;
import io.github.bjconlan.ibkr.proto.ManagedAccountsProto;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            client.reqContractDetails(1, contract("AAPL"));

            Map<Class<? extends com.google.protobuf.Message>, Object> found = awaitAll(events, Duration.ofSeconds(30),
                    ManagedAccountsProto.ManagedAccounts.class,
                    CurrentTimeProto.CurrentTime.class,
                    ContractDataProto.ContractData.class,
                    ContractDataEndProto.ContractDataEnd.class);

            var accounts = (ManagedAccountsProto.ManagedAccounts) found.get(ManagedAccountsProto.ManagedAccounts.class);
            assertFalse(accounts.getAccountsList().isBlank());

            var currentTime = (CurrentTimeProto.CurrentTime) found.get(CurrentTimeProto.CurrentTime.class);
            assertTrue(currentTime.getCurrentTime() > 0);

            var data = (ContractDataProto.ContractData) found.get(ContractDataProto.ContractData.class);
            assertEquals("AAPL", data.getContract().getSymbol());
        }
    }

    private static ContractProto.Contract contract(String symbol) {
        return ContractProto.Contract.newBuilder()
                .setSymbol(symbol).setSecType("STK").setExchange("SMART").setCurrency("USD")
                .build();
    }

    /** Collects matching payloads until every wanted type has been seen, or the timeout elapses. */
    @SafeVarargs
    private static Map<Class<? extends com.google.protobuf.Message>, Object> awaitAll(
            BlockingQueue<IbEvent> events, Duration timeout,
            Class<? extends com.google.protobuf.Message>... wanted) throws InterruptedException {
        List<Class<? extends com.google.protobuf.Message>> remaining = new ArrayList<>(List.of(wanted));
        Map<Class<? extends com.google.protobuf.Message>, Object> found = new HashMap<>();
        long deadline = System.nanoTime() + timeout.toNanos();

        while (!remaining.isEmpty() && System.nanoTime() < deadline) {
            IbEvent event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (event == null) {
                break;
            }
            if (event instanceof IbEvent.Message m) {
                for (Class<? extends com.google.protobuf.Message> type : List.copyOf(remaining)) {
                    if (type.isInstance(m.payload())) {
                        found.put(type, m.payload());
                        remaining.remove(type);
                    }
                }
            }
        }
        assertTrue(remaining.isEmpty(), "timed out waiting for " + remaining);
        return found;
    }
}
