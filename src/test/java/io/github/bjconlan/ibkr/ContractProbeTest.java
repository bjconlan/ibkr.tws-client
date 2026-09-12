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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Read-only contract resolver for exploring markets against a running gateway. Resolves one or
 * more instruments and prints their price/size increments, market rule ids and trading hours.
 *
 * <p>The TWS API has no "list every venue" call; hours come from {@code reqContractDetails} per
 * instrument, so pass a representative contract for each market of interest.
 *
 * <pre>{@code
 * IBKR_PROBE=true IBKR_GATEWAY_PORT=4002 \
 *   IBKR_PROBE_CONTRACTS="EUR:CASH:IDEALPRO:USD,FMG:STK:ASX:AUD,AAPL:STK:SMART:USD" \
 *   mvn test -Dtest=ContractProbeTest
 * }</pre>
 *
 * <p>Format: {@code symbol:secType:exchange:currency[:primaryExch]}, comma separated. The single
 * {@code IBKR_PROBE_SYMBOL}/{@code IBKR_PROBE_SECTYPE}/... variables are used when
 * {@code IBKR_PROBE_CONTRACTS} is absent.
 */
@EnabledIfEnvironmentVariable(named = "IBKR_PROBE", matches = "true")
class ContractProbeTest {

    @Test
    void resolveContracts() throws Exception {
        List<ContractProto.Contract> contracts = contracts();

        BlockingQueue<IbEvent> events = new LinkedBlockingQueue<>();
        TwsConfig config = new TwsConfig(env("IBKR_GATEWAY_HOST", "127.0.0.1"),
                Integer.parseInt(env("IBKR_GATEWAY_PORT", "4002")),
                Integer.parseInt(env("IBKR_CLIENT_ID", "94")), "",
                Duration.ofSeconds(10), 2, Duration.ofMillis(500), 100);

        try (TwsClient client = new TwsClient(config, events::add)) {
            client.connect();
            await(events, ManagedAccountsProto.ManagedAccounts.class, 10);
            for (int i = 0; i < contracts.size(); i++) {
                client.reqContractDetails(i + 1, contracts.get(i));
            }

            int outstanding = contracts.size();
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (outstanding > 0 && System.nanoTime() < deadline) {
                IbEvent event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (event == null) {
                    break;
                }
                if (event instanceof IbEvent.Message m && m.payload() instanceof ContractDataProto.ContractData d) {
                    print(d);
                } else if (event instanceof IbEvent.Message m
                        && m.payload() instanceof ContractDataEndProto.ContractDataEnd) {
                    outstanding--;
                } else if (event instanceof IbEvent.Message m
                        && m.payload() instanceof ErrorMessageProto.ErrorMessage e) {
                    System.out.printf("(error) id=%d code=%d %s%n", e.getId(), e.getErrorCode(), e.getErrorMsg());
                }
            }
        }
    }

    private static void print(ContractDataProto.ContractData d) {
        ContractProto.Contract c = d.getContract();
        ContractDetailsProto.ContractDetails details = d.getContractDetails();
        System.out.printf("%n[reqId %d] %s %s %s %s localSymbol=%s%n",
                d.getReqId(), c.getSymbol(), c.getSecType(), c.getExchange(), c.getCurrency(),
                c.getLocalSymbol());
        System.out.printf("  minTick=%s minSize=%s sizeIncrement=%s marketRuleIds=%s validExchanges=%s%n",
                details.getMinTick(), details.getMinSize(), details.getSizeIncrement(),
                details.getMarketRuleIds(), details.getValidExchanges());
        System.out.printf("  timeZone=%s%n  tradingHours=%s%n  liquidHours=%s%n",
                details.getTimeZoneId(), details.getTradingHours(), details.getLiquidHours());
    }

    private static List<ContractProto.Contract> contracts() {
        String list = System.getenv("IBKR_PROBE_CONTRACTS");
        List<ContractProto.Contract> contracts = new ArrayList<>();
        if (list == null || list.isBlank()) {
            contracts.add(contract(env("IBKR_PROBE_SYMBOL", "EUR"), env("IBKR_PROBE_SECTYPE", "CASH"),
                    env("IBKR_PROBE_EXCHANGE", "IDEALPRO"), env("IBKR_PROBE_CURRENCY", "USD"),
                    System.getenv("IBKR_PROBE_PRIMARY_EXCH")));
            return contracts;
        }
        for (String spec : list.split(",")) {
            String[] parts = spec.trim().split(":");
            if (parts.length < 4) {
                throw new IllegalArgumentException("bad contract spec: " + spec);
            }
            contracts.add(contract(parts[0], parts[1], parts[2], parts[3],
                    parts.length > 4 ? parts[4] : null));
        }
        return contracts;
    }

    private static ContractProto.Contract contract(String symbol, String secType, String exchange,
                                                   String currency, String primaryExch) {
        ContractProto.Contract.Builder b = ContractProto.Contract.newBuilder()
                .setSymbol(symbol).setSecType(secType).setExchange(exchange).setCurrency(currency);
        if (primaryExch != null && !primaryExch.isBlank()) {
            b.setPrimaryExch(primaryExch);
        }
        return b.build();
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
