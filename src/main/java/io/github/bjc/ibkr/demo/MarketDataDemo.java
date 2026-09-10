package io.github.bjc.ibkr.demo;

import io.github.bjc.ibkr.TwsClient;
import io.github.bjc.ibkr.TwsConfig;
import io.github.bjc.ibkr.event.IbEvent;
import io.github.bjc.ibkr.model.Contract;
import io.github.bjc.ibkr.transport.EventHandler;

/**
 * Minimal console demo. Run against a local paper-trading TWS (port 7497) with the API enabled.
 *
 * <pre>{@code
 * mvn -q compile exec:java -Dexec.mainClass=io.github.bjc.ibkr.demo.MarketDataDemo
 * }</pre>
 *
 * <p>The handler is an ordinary method reference and the callback logic is a pattern-matching
 * switch over the sealed {@link IbEvent} hierarchy.
 */
public final class MarketDataDemo {

    private MarketDataDemo() {
    }

    public static void main(String[] args) throws Exception {
        int clientId = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        TwsConfig config = TwsConfig.defaults(clientId);

        EventHandler handler = MarketDataDemo::render;
        try (TwsClient client = new TwsClient(config, handler)) {
            client.connect();
            client.reqMktData(1, Contract.stock("AAPL"), "", false, false);
            client.reqHistoricalData(2, Contract.stock("AAPL"), "", "1 D", "5 mins", true, "TRADES", 1, false);

            // The callback runs on a virtual dispatcher thread; block here for a while.
            Thread.sleep(30_000);
            client.cancelMktData(1);
        }
    }

    private static void render(IbEvent event) {
        switch (event) {
            case IbEvent.Connected c ->
                    System.out.printf("connected: server=%d time=%s%n", c.serverVersion(), c.twsTime());
            case IbEvent.ManagedAccounts m ->
                    System.out.println("accounts: " + m.accounts());
            case IbEvent.NextValidId n ->
                    System.out.println("next valid order id: " + n.orderId());
            case IbEvent.Tick.Price p ->
                    System.out.printf("[%d] price tickType=%d %.4f%n", p.requestId(), p.tickType(), p.price());
            case IbEvent.Tick.Size s ->
                    System.out.printf("[%d] size tickType=%d %s%n", s.requestId(), s.tickType(), s.size());
            case IbEvent.HistoricalBar b ->
                    System.out.printf("[%d] %s O=%.2f H=%.2f L=%.2f C=%.2f V=%s%n",
                            b.requestId(), b.bar().date(), b.bar().open(), b.bar().high(),
                            b.bar().low(), b.bar().close(), b.bar().volume());
            case IbEvent.HistoricalDataEnd e ->
                    System.out.printf("[%d] history done %s .. %s%n", e.requestId(), e.startDate(), e.endDate());
            case IbEvent.OrderStatus s ->
                    System.out.printf("order %d -> %s (filled %s @ %s)%n",
                            s.status().orderId(), s.status().status(), s.status().filled(), s.status().avgFillPrice());
            case IbEvent.Error e ->
                    System.err.printf("[%d] error %d: %s%n", e.requestId(), e.code(), e.message());
            case IbEvent.Disconnected d ->
                    System.out.println("disconnected: " + d.reason());
            default -> {
                // Other events are not interesting to this demo.
            }
        }
    }
}
