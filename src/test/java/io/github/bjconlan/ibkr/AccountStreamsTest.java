package io.github.bjconlan.ibkr;

import io.github.bjconlan.ibkr.event.IbEvent;
import io.github.bjconlan.ibkr.protocol.OutgoingId;
import io.github.bjconlan.ibkr.proto.AccountDataEndProto;
import io.github.bjconlan.ibkr.proto.AccountDataRequestProto;
import io.github.bjconlan.ibkr.proto.AccountSummaryEndProto;
import io.github.bjconlan.ibkr.proto.AccountSummaryProto;
import io.github.bjconlan.ibkr.proto.AccountSummaryRequestProto;
import io.github.bjconlan.ibkr.proto.AccountValueProto;
import io.github.bjconlan.ibkr.proto.AllOpenOrdersRequestProto;
import io.github.bjconlan.ibkr.proto.CancelPnLProto;
import io.github.bjconlan.ibkr.proto.CompletedOrdersEndProto;
import io.github.bjconlan.ibkr.proto.CompletedOrdersRequestProto;
import io.github.bjconlan.ibkr.proto.ErrorMessageProto;
import io.github.bjconlan.ibkr.proto.ExecutionDetailsEndProto;
import io.github.bjconlan.ibkr.proto.ExecutionFilterProto;
import io.github.bjconlan.ibkr.proto.ExecutionRequestProto;
import io.github.bjconlan.ibkr.proto.ManagedAccountsProto;
import io.github.bjconlan.ibkr.proto.OpenOrdersEndProto;
import io.github.bjconlan.ibkr.proto.PnLProto;
import io.github.bjconlan.ibkr.proto.PnLRequestProto;
import io.github.bjconlan.ibkr.proto.PortfolioValueProto;
import io.github.bjconlan.ibkr.proto.PositionEndProto;
import io.github.bjconlan.ibkr.proto.PositionsRequestProto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-only probe for the account and order streams, runnable while markets are closed.
 * Exercises the decoders we have not seen real traffic for yet.
 *
 * <pre>{@code
 * IBKR_STREAMS=true IBKR_GATEWAY_PORT=4002 mvn test -Dtest=AccountStreamsTest
 * }</pre>
 */
@EnabledIfEnvironmentVariable(named = "IBKR_STREAMS", matches = "true")
class AccountStreamsTest {

    @Test
    void accountAndOrderStreams() throws Exception {
        BlockingQueue<IbEvent> events = new LinkedBlockingQueue<>();
        TwsConfig config = new TwsConfig(env("IBKR_GATEWAY_HOST", "127.0.0.1"),
                Integer.parseInt(env("IBKR_GATEWAY_PORT", "4002")),
                Integer.parseInt(env("IBKR_CLIENT_ID", "93")), "",
                Duration.ofSeconds(10), 2, Duration.ofMillis(500), 100);

        Map<String, Integer> counts = new TreeMap<>();
        Map<String, String> samples = new TreeMap<>();

        try (TwsClient client = new TwsClient(config, events::add)) {
            client.connect();
            ManagedAccountsProto.ManagedAccounts accounts = await(events, ManagedAccountsProto.ManagedAccounts.class, 10);
            String account = accounts.getAccountsList().split(",")[0];
            System.out.println("account " + account);

            client.send(OutgoingId.REQ_ALL_OPEN_ORDERS,
                    AllOpenOrdersRequestProto.AllOpenOrdersRequest.getDefaultInstance());
            client.send(OutgoingId.REQ_COMPLETED_ORDERS,
                    CompletedOrdersRequestProto.CompletedOrdersRequest.newBuilder().setApiOnly(false).build());
            client.send(OutgoingId.REQ_EXECUTIONS,
                    ExecutionRequestProto.ExecutionRequest.newBuilder().setReqId(1)
                            .setExecutionFilter(ExecutionFilterProto.ExecutionFilter.getDefaultInstance()).build());
            client.send(OutgoingId.REQ_POSITIONS, PositionsRequestProto.PositionsRequest.getDefaultInstance());
            client.send(OutgoingId.REQ_ACCOUNT_SUMMARY,
                    AccountSummaryRequestProto.AccountSummaryRequest.newBuilder()
                            .setReqId(2).setGroup("All")
                            .setTags("AccountType,NetLiquidation,TotalCashValue,AvailableFunds,BuyingPower,Currency")
                            .build());
            client.send(OutgoingId.REQ_PNL,
                    PnLRequestProto.PnLRequest.newBuilder().setReqId(3).setAccount(account).setModelCode("").build());
            client.reqAccountUpdates(true, "");

            drain(events, Duration.ofSeconds(12), counts, samples);

            client.send(OutgoingId.CANCEL_PNL, CancelPnLProto.CancelPnL.newBuilder().setReqId(3).build());
            client.reqAccountUpdates(false, "");
            drain(events, Duration.ofSeconds(8), counts, samples);
        }

        System.out.println("payload counts: " + counts);
        System.out.println("samples: " + samples);

        assertTrue(counts.getOrDefault("OpenOrdersEnd", 0) > 0, "missing OpenOrdersEnd");
        assertTrue(counts.getOrDefault("CompletedOrdersEnd", 0) > 0, "missing CompletedOrdersEnd");
        assertTrue(counts.getOrDefault("ExecutionDetailsEnd", 0) > 0, "missing ExecutionDetailsEnd");
        assertTrue(counts.getOrDefault("PositionEnd", 0) > 0, "missing PositionEnd");
        assertTrue(counts.getOrDefault("AccountSummaryEnd", 0) > 0, "missing AccountSummaryEnd");
        assertTrue(counts.getOrDefault("AccountDataEnd", 0) > 0, "missing AccountDataEnd");
        assertTrue(counts.getOrDefault("AccountValue", 0) > 0, "missing AccountValue");
        // PnL is pushed on change; on a flat paper account it may be absent entirely.
    }

    private static void drain(BlockingQueue<IbEvent> events, Duration window,
                              Map<String, Integer> counts, Map<String, String> samples)
            throws InterruptedException {
        long deadline = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < deadline) {
            IbEvent event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (event == null) {
                return;
            }
            if (event instanceof IbEvent.Message m) {
                String name = m.payload().getClass().getSimpleName().replace("Proto$", ".").replaceAll(".*\\.", "");
                counts.merge(name, 1, Integer::sum);
                samples.putIfAbsent(name, describe(m.payload()));
            } else if (event instanceof IbEvent.Error e) {
                System.out.printf("(error) %d: %s%n", e.code(), e.message());
            }
        }
    }

    private static String describe(com.google.protobuf.Message payload) {
        if (payload instanceof ErrorMessageProto.ErrorMessage e) {
            return "%d: %s".formatted(e.getErrorCode(), e.getErrorMsg());
        }
        if (payload instanceof AccountSummaryProto.AccountSummary s) {
            return "%s=%s %s".formatted(s.getTag(), s.getValue(), s.getCurrency());
        }
        if (payload instanceof AccountValueProto.AccountValue v) {
            return "%s=%s %s".formatted(v.getKey(), v.getValue(), v.getCurrency());
        }
        if (payload instanceof PortfolioValueProto.PortfolioValue p) {
            return "%s %s pos=%s".formatted(p.getContract().getSymbol(), p.getContract().getCurrency(),
                    p.getPosition());
        }
        if (payload instanceof PnLProto.PnL p) {
            return "daily=%s unrealized=%s realized=%s".formatted(
                    p.getDailyPnL(), p.getUnrealizedPnL(), p.getRealizedPnL());
        }
        return payload.toString().replace('\n', ' ').trim();
    }

    private static String env(String key, String fallback) {
        return System.getenv().getOrDefault(key, fallback);
    }

    private static <T extends com.google.protobuf.Message> T await(BlockingQueue<IbEvent> events,
                                                                   Class<T> type, int seconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < deadline) {
            IbEvent event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (event instanceof IbEvent.Message m && type.isInstance(m.payload())) {
                return type.cast(m.payload());
            }
        }
        throw new AssertionError("did not receive " + type.getSimpleName());
    }
}
