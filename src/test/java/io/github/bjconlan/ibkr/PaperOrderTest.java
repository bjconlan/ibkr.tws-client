package io.github.bjconlan.ibkr;

import io.github.bjconlan.ibkr.event.IbEvent;
import io.github.bjconlan.ibkr.proto.ContractDataEndProto;
import io.github.bjconlan.ibkr.proto.ContractDataProto;
import io.github.bjconlan.ibkr.proto.ContractDetailsProto;
import io.github.bjconlan.ibkr.proto.ContractProto;
import io.github.bjconlan.ibkr.proto.ErrorMessageProto;
import io.github.bjconlan.ibkr.proto.ManagedAccountsProto;
import io.github.bjconlan.ibkr.proto.NextValidIdProto;
import io.github.bjconlan.ibkr.proto.OpenOrderProto;
import io.github.bjconlan.ibkr.proto.OpenOrdersEndProto;
import io.github.bjconlan.ibkr.proto.OrderProto;
import io.github.bjconlan.ibkr.proto.OrderStatusProto;
import io.github.bjconlan.ibkr.proto.PositionProto;
import io.github.bjconlan.ibkr.proto.PositionsRequestProto;
import io.github.bjconlan.ibkr.proto.TickPriceProto;
import io.github.bjconlan.ibkr.proto.TickSnapshotEndProto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in paper-trading order lifecycle probe. Requires a non-read-only paper gateway:
 *
 * <pre>{@code
 * IBKR_ORDER=true IBKR_GATEWAY_PORT=4002 mvn test -Dtest=PaperOrderTest
 * }</pre>
 *
 * <p>Resolves FMG on ASX, sizes a resting limit buy to roughly A$3000, then cancels it. No order
 * is left working.
 */
@EnabledIfEnvironmentVariable(named = "IBKR_ORDER", matches = "true")
class PaperOrderTest {

    private static final double TARGET_NOTIONAL = 3_000.0;

    private static String host() {
        return System.getenv().getOrDefault("IBKR_GATEWAY_HOST", "127.0.0.1");
    }

    private static int port() {
        return Integer.parseInt(System.getenv().getOrDefault("IBKR_GATEWAY_PORT", "4002"));
    }

    private static int clientId() {
        return Integer.parseInt(System.getenv().getOrDefault("IBKR_CLIENT_ID", "98"));
    }

    @Test
    void placeAndCancelRestingLimitOrder() throws Exception {
        BlockingQueue<IbEvent> events = new LinkedBlockingQueue<>();
        TwsConfig config = new TwsConfig(host(), port(), clientId(), "", Duration.ofSeconds(10), 2,
                Duration.ofMillis(500), 100);

        try (TwsClient client = new TwsClient(config, events::add)) {
            client.connect();
            System.out.println("connected: server " + client.serverVersion());
            ManagedAccountsProto.ManagedAccounts accounts = await(events, ManagedAccountsProto.ManagedAccounts.class, 10);
            String account = accounts.getAccountsList().split(",")[0];
            NextValidIdProto.NextValidId nextId = await(events, NextValidIdProto.NextValidId.class, 10);
            int orderId = nextId.getOrderId();
            System.out.println("account " + account + ", next order id: " + orderId);

            client.reqContractDetails(1, fmg());
            ContractDataProto.ContractData data = await(events, ContractDataProto.ContractData.class, 30);
            ContractProto.Contract contract = data.getContract();
            ContractDetailsProto.ContractDetails details = data.getContractDetails();
            System.out.printf("contract: conId=%d %s %s %s %s primaryExch=%s minTick=%s%n",
                    contract.getConId(), contract.getSymbol(), contract.getSecType(),
                    contract.getExchange(), contract.getCurrency(), contract.getPrimaryExch(),
                    details.getMinTick());
            await(events, ContractDataEndProto.ContractDataEnd.class, 30);

            double price = referencePrice(client, events, contract);
            if (price <= 0) {
                // ASX is closed and delayed data may be unavailable; use the last known FMG level.
                price = 16.67;
                System.out.printf("no reference price from gateway; using last known FMG price %.2f%n", price);
            }
            // ContractDetails.minTick is the smallest tick across all price bands (0.001 for ASX),
            // but FMG trades in 0.01 increments at this price; reqMarketRule gives the full schedule.
            double tick = 0.01;
            int quantity = Math.max(1, (int) Math.round(TARGET_NOTIONAL / price));
            double limit = Math.max(tick, Math.floor((price * 0.99) / tick) * tick);
            System.out.printf("reference price %.3f, limit %.3f, quantity %d, notional A$%.2f%n",
                    price, limit, quantity, limit * quantity);

            client.placeOrder(orderId, contract, OrderProto.Order.newBuilder()
                    .setAction("BUY")
                    .setOrderType("LMT")
                    .setTotalQuantity(Integer.toString(quantity))
                    .setLmtPrice(limit)
                    .setTif("GTC")
                    .setAccount(account)
                    .setTransmit(true)
                    .build());

            OrderStatusProto.OrderStatus status = awaitOrderStatus(events, orderId);
            System.out.println("order status: " + status.getStatus() + " (id " + status.getOrderId()
                    + ", filled " + status.getFilled() + ")");

            client.reqOpenOrders();
            OpenOrderProto.OpenOrder open = await(events, OpenOrderProto.OpenOrder.class, 15);
            await(events, OpenOrdersEndProto.OpenOrdersEnd.class, 15);
            System.out.printf("open order: %s %s %s qty=%s lmt=%.3f%n",
                    open.getOrder().getAction(), open.getOrder().getOrderType(),
                    open.getContract().getSymbol(), open.getOrder().getTotalQuantity(),
                    open.getOrder().getLmtPrice());

            client.cancelOrder(orderId);
            OrderStatusProto.OrderStatus cancelled = awaitOrderStatus(events, orderId, "Cancelled");
            System.out.println("cancel status: " + cancelled.getStatus());

            client.send(io.github.bjconlan.ibkr.protocol.OutgoingId.REQ_POSITIONS,
                    PositionsRequestProto.PositionsRequest.getDefaultInstance());
            Thread.sleep(2_000);
        }
    }

    private static ContractProto.Contract fmg() {
        return ContractProto.Contract.newBuilder()
                .setSymbol("FMG")
                .setSecType("STK")
                .setExchange("ASX")
                .setPrimaryExch("ASX")
                .setCurrency("AUD")
                .build();
    }

    /** Delayed snapshot last/close, falling back to the most recent delayed tick seen. */
    private static double referencePrice(TwsClient client, BlockingQueue<IbEvent> events,
                                         ContractProto.Contract contract) throws InterruptedException {
        client.setMarketDataType(3); // delayed
        client.reqMktData(2, contract, "", true, false);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        double price = 0;
        while (System.nanoTime() < deadline) {
            IbEvent event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (event == null) {
                break;
            }
            if (event instanceof IbEvent.Message m && m.payload() instanceof TickPriceProto.TickPrice t) {
                int type = t.getTickType();
                if (type == 4 || type == 9 || type == 68 || type == 75) {
                    if (t.getPrice() > 0 && (price == 0 || type == 4 || type == 68)) {
                        price = t.getPrice();
                    }
                }
            }
            if (event instanceof IbEvent.Message m && m.payload() instanceof TickSnapshotEndProto.TickSnapshotEnd) {
                break;
            }
            print(event);
        }
        return price;
    }

    private static OrderStatusProto.OrderStatus awaitOrderStatus(BlockingQueue<IbEvent> events, int orderId)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            IbEvent event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (event instanceof IbEvent.Message m && m.payload() instanceof OrderStatusProto.OrderStatus s
                    && s.getOrderId() == orderId) {
                return s;
            }
            if (event instanceof IbEvent.Message m && m.payload() instanceof ErrorMessageProto.ErrorMessage e
                    && e.getId() == orderId) {
                throw new AssertionError("order %d rejected: %s".formatted(orderId, e.getErrorMsg()));
            }
            print(event);
        }
        throw new AssertionError("no order status for " + orderId);
    }

    private static OrderStatusProto.OrderStatus awaitOrderStatus(BlockingQueue<IbEvent> events, int orderId,
                                                                String wanted) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            IbEvent event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (event instanceof IbEvent.Message m && m.payload() instanceof OrderStatusProto.OrderStatus s
                    && s.getOrderId() == orderId && wanted.equalsIgnoreCase(s.getStatus())) {
                return s;
            }
            print(event);
        }
        throw new AssertionError("no status '" + wanted + "' for " + orderId);
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
            print(event);
        }
        throw new AssertionError("did not receive " + type.getSimpleName());
    }

    private static void print(IbEvent event) {
        if (event == null) {
            return;
        }
        if (event instanceof IbEvent.Message m && m.payload() instanceof ErrorMessageProto.ErrorMessage e) {
            System.out.printf("(error) id=%d code=%d %s%n", e.getId(), e.getErrorCode(), e.getErrorMsg());
        } else if (event instanceof IbEvent.Message m && m.payload() instanceof OrderStatusProto.OrderStatus s) {
            System.out.printf("(order) id=%d %s filled=%s@%s%n",
                    s.getOrderId(), s.getStatus(), s.getFilled(), s.getAvgFillPrice());
        } else if (event instanceof IbEvent.Message m && m.payload() instanceof PositionProto.Position p) {
            System.out.printf("(position) %s %s %s%n",
                    p.getContract().getSymbol(), p.getPosition(), p.getAvgCost());
        }
    }
}
