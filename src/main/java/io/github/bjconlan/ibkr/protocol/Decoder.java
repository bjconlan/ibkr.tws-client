package io.github.bjconlan.ibkr.protocol;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.bjconlan.ibkr.event.IbEvent;
import io.github.bjconlan.ibkr.model.AccountValue;
import io.github.bjconlan.ibkr.model.Bar;
import io.github.bjconlan.ibkr.model.OrderStatusData;
import io.github.bjconlan.ibkr.model.PortfolioValue;
import io.github.bjconlan.ibkr.model.TickAttrib;
import io.github.bjconlan.ibkr.proto.AccountDataEndProto;
import io.github.bjconlan.ibkr.proto.AccountUpdateTimeProto;
import io.github.bjconlan.ibkr.proto.AccountValueProto;
import io.github.bjconlan.ibkr.proto.CommissionAndFeesReportProto;
import io.github.bjconlan.ibkr.proto.ContractDataEndProto;
import io.github.bjconlan.ibkr.proto.ContractDataProto;
import io.github.bjconlan.ibkr.proto.CurrentTimeProto;
import io.github.bjconlan.ibkr.proto.ErrorMessageProto;
import io.github.bjconlan.ibkr.proto.ExecutionDetailsEndProto;
import io.github.bjconlan.ibkr.proto.ExecutionDetailsProto;
import io.github.bjconlan.ibkr.proto.HistoricalDataBarProto;
import io.github.bjconlan.ibkr.proto.HistoricalDataEndProto;
import io.github.bjconlan.ibkr.proto.HistoricalDataProto;
import io.github.bjconlan.ibkr.proto.ManagedAccountsProto;
import io.github.bjconlan.ibkr.proto.MarketDataTypeProto;
import io.github.bjconlan.ibkr.proto.NextValidIdProto;
import io.github.bjconlan.ibkr.proto.OpenOrderProto;
import io.github.bjconlan.ibkr.proto.OrderStatusProto;
import io.github.bjconlan.ibkr.proto.PortfolioValueProto;
import io.github.bjconlan.ibkr.proto.PositionEndProto;
import io.github.bjconlan.ibkr.proto.PositionProto;
import io.github.bjconlan.ibkr.proto.TickGenericProto;
import io.github.bjconlan.ibkr.proto.TickOptionComputationProto;
import io.github.bjconlan.ibkr.proto.TickPriceProto;
import io.github.bjconlan.ibkr.proto.TickReqParamsProto;
import io.github.bjconlan.ibkr.proto.TickSizeProto;
import io.github.bjconlan.ibkr.proto.TickSnapshotEndProto;
import io.github.bjconlan.ibkr.proto.TickStringProto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decodes a framed server message into zero or more {@link IbEvent}s.
 *
 * <p>The wire format for a protobuf message is {@code [msgId:4][protobuf payload]}. A message
 * whose id is at most {@link Wire#PROTOBUF_MSG_ID} is a legacy text message; this client only
 * speaks the protobuf variant (server version {@value Wire#MIN_SERVER_VER_PROTOBUF} and later) and
 * reports anything else as an error event rather than guessing.
 *
 * <p>The switch over {@link IncomingId} is exhaustive by construction: the compiler will refuse
 * to build the class if a new id is added to the enum and not handled here.
 */
public final class Decoder {

    private static final int NO_VALID_ID = -1;
    private static final int UNKNOWN_ID = 505;

    /** Decodes one message body, without its outer length prefix. */
    public List<IbEvent> decode(byte[] payload) {
        int rawId = Wire.readInt(payload, 0);
        int baseId = rawId > Wire.PROTOBUF_MSG_ID ? rawId - Wire.PROTOBUF_MSG_ID : rawId;
        boolean protobuf = rawId > Wire.PROTOBUF_MSG_ID;

        IncomingId id = IncomingId.fromId(baseId);
        if (id == null) {
            return List.of(error(NO_VALID_ID, UNKNOWN_ID, "unknown message id " + rawId));
        }
        if (!protobuf) {
            return List.of(error(NO_VALID_ID, UNKNOWN_ID,
                    "legacy text message %d is not supported; server must report version >= %d"
                            .formatted(rawId, Wire.MIN_SERVER_VER_PROTOBUF)));
        }
        try {
            return switch (id) {
                case TICK_PRICE -> List.of(tickPrice(payload));
                case TICK_SIZE -> List.of(tickSize(payload));
                case TICK_GENERIC -> List.of(tickGeneric(payload));
                case TICK_STRING -> List.of(tickString(payload));
                case TICK_OPTION_COMPUTATION -> List.of(tickOptionComputation(payload));
                case TICK_REQ_PARAMS -> List.of(tickReqParams(payload));
                case TICK_SNAPSHOT_END -> List.of(tickSnapshotEnd(payload));
                case ERR_MSG -> List.of(errorMessage(payload));
                case NEXT_VALID_ID -> List.of(nextValidId(payload));
                case CURRENT_TIME -> List.of(currentTime(payload));
                case MANAGED_ACCTS -> List.of(managedAccounts(payload));
                case MARKET_DATA_TYPE -> List.of(marketDataType(payload));
                case ACCT_UPDATE_TIME -> List.of(accountUpdateTime(payload));
                case CONTRACT_DATA -> contractData(payload);
                case CONTRACT_DATA_END -> List.of(contractDataEnd(payload));
                case HISTORICAL_DATA -> historicalData(payload);
                case HISTORICAL_DATA_END -> List.of(historicalDataEnd(payload));
                case ORDER_STATUS -> List.of(orderStatus(payload));
                case OPEN_ORDER -> List.of(openOrder(payload));
                case OPEN_ORDER_END -> List.of(new IbEvent.OpenOrdersEnd());
                case ACCT_VALUE -> List.of(accountValue(payload));
                case PORTFOLIO_VALUE -> List.of(portfolioValue(payload));
                case ACCT_DOWNLOAD_END -> List.of(accountDownloadEnd(payload));
                case POSITION -> List.of(position(payload));
                case POSITION_END -> List.of(new IbEvent.PositionsEnd());
                case EXECUTION_DATA -> List.of(executionDetails(payload));
                case EXECUTION_DATA_END -> List.of(executionsEnd(payload));
                case COMMISSION_AND_FEES_REPORT -> List.of(
                        new IbEvent.CommissionReportReceived(
                                ProtoMapper.fromProto(CommissionAndFeesReportProto.CommissionAndFeesReport
                                        .parseFrom(slice(payload)))));
            };
        } catch (InvalidProtocolBufferException e) {
            return List.of(error(NO_VALID_ID, UNKNOWN_ID,
                    "malformed %s message: %s".formatted(id, e.getMessage())));
        }
    }

    // ------------------------------------------------------------ tick messages

    private IbEvent tickPrice(byte[] payload) throws InvalidProtocolBufferException {
        TickPriceProto.TickPrice p = TickPriceProto.TickPrice.parseFrom(slice(payload));
        return new IbEvent.Tick.Price(
                intOr(p.hasReqId(), p::getReqId, NO_VALID_ID),
                intOr(p.hasTickType(), p::getTickType, 0),
                p.hasPrice() ? p.getPrice() : 0.0,
                str(p.hasSize(), p::getSize),
                TickAttrib.fromMask(p.hasAttrMask() ? p.getAttrMask() : 0));
    }

    private IbEvent tickSize(byte[] payload) throws InvalidProtocolBufferException {
        TickSizeProto.TickSize p = TickSizeProto.TickSize.parseFrom(slice(payload));
        return new IbEvent.Tick.Size(
                intOr(p.hasReqId(), p::getReqId, NO_VALID_ID),
                intOr(p.hasTickType(), p::getTickType, 0),
                str(p.hasSize(), p::getSize));
    }

    private IbEvent tickGeneric(byte[] payload) throws InvalidProtocolBufferException {
        TickGenericProto.TickGeneric p = TickGenericProto.TickGeneric.parseFrom(slice(payload));
        return new IbEvent.Tick.Generic(
                intOr(p.hasReqId(), p::getReqId, NO_VALID_ID),
                intOr(p.hasTickType(), p::getTickType, 0),
                p.hasValue() ? p.getValue() : 0.0);
    }

    private IbEvent tickString(byte[] payload) throws InvalidProtocolBufferException {
        TickStringProto.TickString p = TickStringProto.TickString.parseFrom(slice(payload));
        return new IbEvent.Tick.Text(
                intOr(p.hasReqId(), p::getReqId, NO_VALID_ID),
                intOr(p.hasTickType(), p::getTickType, 0),
                str(p.hasValue(), p::getValue));
    }

    private IbEvent tickOptionComputation(byte[] payload) throws InvalidProtocolBufferException {
        TickOptionComputationProto.TickOptionComputation p =
                TickOptionComputationProto.TickOptionComputation.parseFrom(slice(payload));
        return new IbEvent.Tick.OptionComputation(
                intOr(p.hasReqId(), p::getReqId, NO_VALID_ID),
                intOr(p.hasTickType(), p::getTickType, 0),
                intOr(p.hasTickAttrib(), p::getTickAttrib, 0),
                p.hasImpliedVol() ? p.getImpliedVol() : 0.0,
                p.hasDelta() ? p.getDelta() : 0.0,
                p.hasOptPrice() ? p.getOptPrice() : 0.0,
                p.hasPvDividend() ? p.getPvDividend() : 0.0,
                p.hasGamma() ? p.getGamma() : 0.0,
                p.hasVega() ? p.getVega() : 0.0,
                p.hasTheta() ? p.getTheta() : 0.0,
                p.hasUndPrice() ? p.getUndPrice() : 0.0);
    }

    private IbEvent tickReqParams(byte[] payload) throws InvalidProtocolBufferException {
        TickReqParamsProto.TickReqParams p = TickReqParamsProto.TickReqParams.parseFrom(slice(payload));
        return new IbEvent.Tick.RequestParams(
                intOr(p.hasReqId(), p::getReqId, NO_VALID_ID),
                str(p.hasMinTick(), p::getMinTick),
                str(p.hasBboExchange(), p::getBboExchange),
                intOr(p.hasSnapshotPermissions(), p::getSnapshotPermissions, 0),
                str(p.hasLastPricePrecision(), p::getLastPricePrecision),
                str(p.hasLastSizePrecision(), p::getLastSizePrecision));
    }

    private IbEvent tickSnapshotEnd(byte[] payload) throws InvalidProtocolBufferException {
        TickSnapshotEndProto.TickSnapshotEnd p = TickSnapshotEndProto.TickSnapshotEnd.parseFrom(slice(payload));
        return new IbEvent.Tick.SnapshotEnd(intOr(p.hasReqId(), p::getReqId, NO_VALID_ID));
    }

    // ------------------------------------------------------------ misc messages

    private IbEvent errorMessage(byte[] payload) throws InvalidProtocolBufferException {
        ErrorMessageProto.ErrorMessage p = ErrorMessageProto.ErrorMessage.parseFrom(slice(payload));
        return error(
                intOr(p.hasId(), p::getId, NO_VALID_ID),
                intOr(p.hasErrorCode(), p::getErrorCode, 0),
                str(p.hasErrorMsg(), p::getErrorMsg),
                p.hasErrorTime() ? p.getErrorTime() : 0L,
                str(p.hasAdvancedOrderRejectJson(), p::getAdvancedOrderRejectJson));
    }

    private IbEvent nextValidId(byte[] payload) throws InvalidProtocolBufferException {
        NextValidIdProto.NextValidId p = NextValidIdProto.NextValidId.parseFrom(slice(payload));
        return new IbEvent.NextValidId(intOr(p.hasOrderId(), p::getOrderId, 0));
    }

    private IbEvent currentTime(byte[] payload) throws InvalidProtocolBufferException {
        CurrentTimeProto.CurrentTime p = CurrentTimeProto.CurrentTime.parseFrom(slice(payload));
        return new IbEvent.CurrentTime(p.hasCurrentTime() ? p.getCurrentTime() : 0L);
    }

    private IbEvent managedAccounts(byte[] payload) throws InvalidProtocolBufferException {
        ManagedAccountsProto.ManagedAccounts p = ManagedAccountsProto.ManagedAccounts.parseFrom(slice(payload));
        String csv = str(p.hasAccountsList(), p::getAccountsList);
        List<String> accounts = csv == null || csv.isBlank()
                ? List.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new IbEvent.ManagedAccounts(accounts);
    }

    private IbEvent marketDataType(byte[] payload) throws InvalidProtocolBufferException {
        MarketDataTypeProto.MarketDataType p = MarketDataTypeProto.MarketDataType.parseFrom(slice(payload));
        return new IbEvent.MarketDataType(
                intOr(p.hasReqId(), p::getReqId, NO_VALID_ID),
                intOr(p.hasMarketDataType(), p::getMarketDataType, 0));
    }

    private IbEvent accountUpdateTime(byte[] payload) throws InvalidProtocolBufferException {
        AccountUpdateTimeProto.AccountUpdateTime p = AccountUpdateTimeProto.AccountUpdateTime.parseFrom(slice(payload));
        return new IbEvent.AccountUpdateTime(str(p.hasTimeStamp(), p::getTimeStamp));
    }

    // ------------------------------------------------------------ contract details

    private List<IbEvent> contractData(byte[] payload) throws InvalidProtocolBufferException {
        ContractDataProto.ContractData p = ContractDataProto.ContractData.parseFrom(slice(payload));
        if (!p.hasContract() || !p.hasContractDetails()) {
            return List.of();
        }
        int reqId = intOr(p.hasReqId(), p::getReqId, NO_VALID_ID);
        return List.of(new IbEvent.ContractDetailsReceived(
                reqId, ProtoMapper.fromProto(p.getContract(), p.getContractDetails())));
    }

    private IbEvent contractDataEnd(byte[] payload) throws InvalidProtocolBufferException {
        ContractDataEndProto.ContractDataEnd p = ContractDataEndProto.ContractDataEnd.parseFrom(slice(payload));
        return new IbEvent.ContractDetailsEnd(intOr(p.hasReqId(), p::getReqId, NO_VALID_ID));
    }

    // ------------------------------------------------------------ historical data

    private List<IbEvent> historicalData(byte[] payload) throws InvalidProtocolBufferException {
        HistoricalDataProto.HistoricalData p = HistoricalDataProto.HistoricalData.parseFrom(slice(payload));
        int reqId = intOr(p.hasReqId(), p::getReqId, NO_VALID_ID);
        List<IbEvent> events = new ArrayList<>(p.getHistoricalDataBarsCount());
        for (HistoricalDataBarProto.HistoricalDataBar bar : p.getHistoricalDataBarsList()) {
            events.add(new IbEvent.HistoricalBar(reqId, new Bar(
                    str(bar.hasDate(), bar::getDate),
                    bar.hasOpen() ? bar.getOpen() : 0.0,
                    bar.hasHigh() ? bar.getHigh() : 0.0,
                    bar.hasLow() ? bar.getLow() : 0.0,
                    bar.hasClose() ? bar.getClose() : 0.0,
                    str(bar.hasVolume(), bar::getVolume),
                    str(bar.hasWAP(), bar::getWAP),
                    bar.hasBarCount() ? bar.getBarCount() : null)));
        }
        return events;
    }

    private IbEvent historicalDataEnd(byte[] payload) throws InvalidProtocolBufferException {
        HistoricalDataEndProto.HistoricalDataEnd p = HistoricalDataEndProto.HistoricalDataEnd.parseFrom(slice(payload));
        return new IbEvent.HistoricalDataEnd(
                intOr(p.hasReqId(), p::getReqId, NO_VALID_ID),
                str(p.hasStartDateStr(), p::getStartDateStr),
                str(p.hasEndDateStr(), p::getEndDateStr));
    }

    // ------------------------------------------------------------ orders

    private IbEvent orderStatus(byte[] payload) throws InvalidProtocolBufferException {
        OrderStatusProto.OrderStatus p = OrderStatusProto.OrderStatus.parseFrom(slice(payload));
        return new IbEvent.OrderStatus(new OrderStatusData(
                intOr(p.hasOrderId(), p::getOrderId, 0),
                str(p.hasStatus(), p::getStatus),
                str(p.hasFilled(), p::getFilled),
                str(p.hasRemaining(), p::getRemaining),
                p.hasAvgFillPrice() ? p.getAvgFillPrice() : null,
                p.hasPermId() ? p.getPermId() : null,
                p.hasParentId() ? p.getParentId() : null,
                p.hasLastFillPrice() ? p.getLastFillPrice() : null,
                p.hasClientId() ? p.getClientId() : null,
                str(p.hasWhyHeld(), p::getWhyHeld),
                p.hasMktCapPrice() ? p.getMktCapPrice() : null));
    }

    private IbEvent openOrder(byte[] payload) throws InvalidProtocolBufferException {
        OpenOrderProto.OpenOrder p = OpenOrderProto.OpenOrder.parseFrom(slice(payload));
        int orderId = intOr(p.hasOrderId(), p::getOrderId, 0);
        if (!p.hasContract() || !p.hasOrder() || !p.hasOrderState()) {
            return new IbEvent.Error(orderId, System.currentTimeMillis(), UNKNOWN_ID,
                    "open order message missing contract, order or state", null);
        }
        return new IbEvent.OpenOrder(
                orderId,
                ProtoMapper.fromProto(p.getContract()),
                ProtoMapper.fromProto(p.getOrder()),
                ProtoMapper.fromProto(p.getOrderState()));
    }

    // ------------------------------------------------------------ account and positions

    private IbEvent accountValue(byte[] payload) throws InvalidProtocolBufferException {
        AccountValueProto.AccountValue p = AccountValueProto.AccountValue.parseFrom(slice(payload));
        return new IbEvent.AccountValueUpdate(new AccountValue(
                str(p.hasKey(), p::getKey),
                str(p.hasValue(), p::getValue),
                str(p.hasCurrency(), p::getCurrency),
                str(p.hasAccountName(), p::getAccountName)));
    }

    private IbEvent portfolioValue(byte[] payload) throws InvalidProtocolBufferException {
        PortfolioValueProto.PortfolioValue p = PortfolioValueProto.PortfolioValue.parseFrom(slice(payload));
        return new IbEvent.PortfolioValueUpdate(new PortfolioValue(
                p.hasContract() ? ProtoMapper.fromProto(p.getContract()) : null,
                str(p.hasPosition(), p::getPosition),
                p.hasMarketPrice() ? p.getMarketPrice() : null,
                p.hasMarketValue() ? p.getMarketValue() : null,
                p.hasAverageCost() ? p.getAverageCost() : null,
                p.hasUnrealizedPNL() ? p.getUnrealizedPNL() : null,
                p.hasRealizedPNL() ? p.getRealizedPNL() : null,
                str(p.hasAccountName(), p::getAccountName)));
    }

    private IbEvent accountDownloadEnd(byte[] payload) throws InvalidProtocolBufferException {
        AccountDataEndProto.AccountDataEnd p = AccountDataEndProto.AccountDataEnd.parseFrom(slice(payload));
        return new IbEvent.AccountDownloadEnd(str(p.hasAccountName(), p::getAccountName));
    }

    private IbEvent position(byte[] payload) throws InvalidProtocolBufferException {
        PositionProto.Position p = PositionProto.Position.parseFrom(slice(payload));
        return new IbEvent.PositionUpdate(ProtoMapper.fromProto(p));
    }

    // ------------------------------------------------------------ executions

    private IbEvent executionDetails(byte[] payload) throws InvalidProtocolBufferException {
        ExecutionDetailsProto.ExecutionDetails p = ExecutionDetailsProto.ExecutionDetails.parseFrom(slice(payload));
        return new IbEvent.ExecutionDetails(
                intOr(p.hasReqId(), p::getReqId, NO_VALID_ID),
                p.hasContract() ? ProtoMapper.fromProto(p.getContract()) : null,
                p.hasExecution() ? ProtoMapper.fromProto(p.getExecution()) : null);
    }

    private IbEvent executionsEnd(byte[] payload) throws InvalidProtocolBufferException {
        ExecutionDetailsEndProto.ExecutionDetailsEnd p = ExecutionDetailsEndProto.ExecutionDetailsEnd.parseFrom(slice(payload));
        return new IbEvent.ExecutionsEnd(intOr(p.hasReqId(), p::getReqId, NO_VALID_ID));
    }

    // ------------------------------------------------------------ helpers

    /** Strips the 4-byte message id so the remainder can be parsed as a protobuf body. */
    private static byte[] slice(byte[] payload) {
        return Arrays.copyOfRange(payload, 4, payload.length);
    }

    private static IbEvent error(int requestId, int code, String message) {
        return error(requestId, code, message, System.currentTimeMillis(), null);
    }

    private static IbEvent error(int requestId, int code, String message, long epochMillis, String rejectJson) {
        return new IbEvent.Error(requestId, epochMillis, code, message == null ? "" : message, rejectJson);
    }

    private static String str(boolean present, java.util.function.Supplier<String> getter) {
        return present ? getter.get() : null;
    }

    private static int intOr(boolean present, java.util.function.IntSupplier getter, int fallback) {
        return present ? getter.getAsInt() : fallback;
    }
}
