package io.github.bjconlan.ibkr.event;

import io.github.bjconlan.ibkr.model.AccountValue;
import io.github.bjconlan.ibkr.model.Bar;
import io.github.bjconlan.ibkr.model.CommissionReport;
import io.github.bjconlan.ibkr.model.Contract;
import io.github.bjconlan.ibkr.model.ContractDetails;
import io.github.bjconlan.ibkr.model.Execution;
import io.github.bjconlan.ibkr.model.Order;
import io.github.bjconlan.ibkr.model.OrderState;
import io.github.bjconlan.ibkr.model.OrderStatusData;
import io.github.bjconlan.ibkr.model.PortfolioValue;
import io.github.bjconlan.ibkr.model.Position;
import io.github.bjconlan.ibkr.model.TickAttrib;

import java.util.List;

/**
 * Everything the server can tell the client, as one sealed hierarchy.
 *
 * <p>Because the hierarchy is sealed, a handler can exhaustively pattern match:
 *
 * <pre>{@code
 * switch (event) {
 *     case IbEvent.Tick.Price p  -> render(p.requestId(), p.price());
 *     case IbEvent.Tick.Size s   -> render(s.requestId(), s.size());
 *     case IbEvent.Error e       -> log.error("{}: {}", e.code(), e.message());
 *     case IbEvent.OrderStatus o -> track(o.status().orderId(), o.status().status());
 *     default -> { }
 * }
 * }</pre>
 *
 * <p>Events are delivered in the order the server produced them by a single dispatcher thread,
 * so tick streams stay coherent even when the handler blocks.
 */
public sealed interface IbEvent {

    /** Handshake completed; {@code serverVersion} gates which message variants are in use. */
    record Connected(int serverVersion, String twsTime) implements IbEvent {}

    /** The connection ended, either locally or because the socket failed. */
    record Disconnected(String reason, Throwable cause) implements IbEvent {}

    /** An error or informational message from TWS. Informational codes have {@code code < 2100}. */
    record Error(int requestId, long epochMillis, int code, String message,
                 String advancedOrderRejectJson) implements IbEvent {}

    /** Response to a current-time request, in epoch seconds. */
    record CurrentTime(long epochSeconds) implements IbEvent {}

    /** The next order id the client may use. */
    record NextValidId(int orderId) implements IbEvent {}

    /** Comma-separated account ids managed by this login, split for convenience. */
    record ManagedAccounts(List<String> accounts) implements IbEvent {}

    /** The active market data type (1 live, 2 frozen, 3 delayed, 4 delayed-frozen). */
    record MarketDataType(int requestId, int type) implements IbEvent {}

    /** Timestamp of the last account update. */
    record AccountUpdateTime(String timestamp) implements IbEvent {}

    /** A streaming market data message. */
    sealed interface Tick extends IbEvent {
        int requestId();

        record Price(int requestId, int tickType, double price, String size, TickAttrib attrib) implements Tick {}

        record Size(int requestId, int tickType, String size) implements Tick {}

        record Generic(int requestId, int tickType, double value) implements Tick {}

        record Text(int requestId, int tickType, String value) implements Tick {}

        record OptionComputation(int requestId, int tickType, int tickAttrib, double impliedVolatility,
                                 double delta, double optionPrice, double presentValueDividend,
                                 double gamma, double vega, double theta,
                                 double underlyingPrice) implements Tick {}

        record RequestParams(int requestId, String minTick, String bboExchange, int snapshotPermissions,
                             String lastPricePrecision, String lastSizePrecision) implements Tick {}

        record SnapshotEnd(int requestId) implements Tick {}
    }

    record ContractDetailsReceived(int requestId, ContractDetails details) implements IbEvent {}

    record ContractDetailsEnd(int requestId) implements IbEvent {}

    record HistoricalBar(int requestId, Bar bar) implements IbEvent {}

    record HistoricalDataEnd(int requestId, String startDate, String endDate) implements IbEvent {}

    record OrderStatus(OrderStatusData status) implements IbEvent {}

    record OpenOrder(int orderId, Contract contract, Order order, OrderState orderState) implements IbEvent {}

    /** End of the open order stream. */
    record OpenOrdersEnd() implements IbEvent {}

    record PositionUpdate(Position position) implements IbEvent {}

    record PositionsEnd() implements IbEvent {}

    record ExecutionDetails(int requestId, Contract contract, Execution execution) implements IbEvent {}

    record ExecutionsEnd(int requestId) implements IbEvent {}

    record CommissionReportReceived(CommissionReport report) implements IbEvent {}

    record AccountValueUpdate(AccountValue value) implements IbEvent {}

    record PortfolioValueUpdate(PortfolioValue value) implements IbEvent {}

    record AccountDownloadEnd(String accountName) implements IbEvent {}
}
