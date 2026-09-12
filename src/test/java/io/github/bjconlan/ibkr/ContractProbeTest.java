package io.github.bjconlan.ibkr;

import io.github.bjconlan.ibkr.event.IbEvent;
import io.github.bjconlan.ibkr.proto.ContractDataEndProto;
import io.github.bjconlan.ibkr.proto.ContractDataProto;
import io.github.bjconlan.ibkr.proto.ContractDetailsProto;
import io.github.bjconlan.ibkr.proto.ContractProto;
import io.github.bjconlan.ibkr.proto.ErrorMessageProto;
import io.github.bjconlan.ibkr.proto.ManagedAccountsProto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Read-only contract resolver for exploring symbols against a running gateway. Prints the
 * contract, its price/size increments and trading hours, then exits.
 *
 * <pre>{@code
 * IBKR_PROBE=true IBKR_GATEWAY_PORT=4002 \
 *   IBKR_PROBE_SYMBOL=EUR IBKR_PROBE_SECTYPE=CASH IBKR_PROBE_EXCHANGE=IDEALPRO IBKR_PROBE_CURRENCY=USD \
 *   mvn test -Dtest=ContractProbeTest
 * }</pre>
 */
@EnabledIfEnvironmentVariable(named = "IBKR_PROBE", matches = "true")
class ContractProbeTest {

    @Test
    void resolveContract() throws Exception {
        String symbol = env("IBKR_PROBE_SYMBOL", "EUR");
        String secType = env("IBKR_PROBE_SECTYPE", "CASH");
        String exchange = env("IBKR_PROBE_EXCHANGE", "IDEALPRO");
        String currency = env("IBKR_PROBE_CURRENCY", "USD");

        BlockingQueue<IbEvent> events = new LinkedBlockingQueue<>();
        TwsConfig config = new TwsConfig(env("IBKR_GATEWAY_HOST", "127.0.0.1"),
                Integer.parseInt(env("IBKR_GATEWAY_PORT", "4002")),
                Integer.parseInt(env("IBKR_CLIENT_ID", "94")), "",
                Duration.ofSeconds(10), 2, Duration.ofMillis(500), 100);

        ContractProto.Contract.Builder contract = ContractProto.Contract.newBuilder()
                .setSymbol(symbol).setSecType(secType).setExchange(exchange).setCurrency(currency);
        String primaryExch = System.getenv("IBKR_PROBE_PRIMARY_EXCH");
        if (primaryExch != null && !primaryExch.isBlank()) {
            contract.setPrimaryExch(primaryExch);
        }

        try (TwsClient client = new TwsClient(config, events::add)) {
            client.connect();
            await(events, ManagedAccountsProto.ManagedAccounts.class, 10);
            client.reqContractDetails(1, contract.build());

            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                IbEvent event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (event == null) {
                    break;
                }
                if (event instanceof IbEvent.Message m && m.payload() instanceof ContractDataProto.ContractData d) {
                    ContractProto.Contract c = d.getContract();
                    ContractDetailsProto.ContractDetails details = d.getContractDetails();
                    System.out.printf(
                            "conId=%d %s %s %s %s localSymbol=%s tradingClass=%s multiplier=%s%n"
                                    + "  minTick=%s minSize=%s sizeIncrement=%s suggestedSizeIncrement=%s%n"
                                    + "  timeZone=%s marketRuleIds=%s%n  tradingHours=%s%n"
                                    + "  longName=%s validExchanges=%s%n",
                            c.getConId(), c.getSymbol(), c.getSecType(), c.getExchange(), c.getCurrency(),
                            c.getLocalSymbol(), c.getTradingClass(), c.getMultiplier(),
                            details.getMinTick(), details.getMinSize(), details.getSizeIncrement(),
                            details.getSuggestedSizeIncrement(),
                            details.getTimeZoneId(), details.getMarketRuleIds(), details.getTradingHours(),
                            details.getLongName(), details.getValidExchanges());
                } else if (event instanceof IbEvent.Message m
                        && m.payload() instanceof ContractDataEndProto.ContractDataEnd) {
                    System.out.println("(contract data end)");
                    break;
                } else if (event instanceof IbEvent.Message m
                        && m.payload() instanceof ErrorMessageProto.ErrorMessage e) {
                    System.out.printf("(error) id=%d code=%d %s%n", e.getId(), e.getErrorCode(), e.getErrorMsg());
                }
            }
        }
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
