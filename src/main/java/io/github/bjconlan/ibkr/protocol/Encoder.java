package io.github.bjconlan.ibkr.protocol;

import io.github.bjconlan.ibkr.model.Contract;
import io.github.bjconlan.ibkr.model.ExecutionFilter;
import io.github.bjconlan.ibkr.model.Order;
import io.github.bjconlan.ibkr.proto.AccountDataRequestProto;
import io.github.bjconlan.ibkr.proto.CancelContractDataProto;
import io.github.bjconlan.ibkr.proto.CancelHistoricalDataProto;
import io.github.bjconlan.ibkr.proto.CancelMarketDataProto;
import io.github.bjconlan.ibkr.proto.CancelOrderRequestProto;
import io.github.bjconlan.ibkr.proto.CancelPositionsProto;
import io.github.bjconlan.ibkr.proto.ContractDataRequestProto;
import io.github.bjconlan.ibkr.proto.CurrentTimeRequestProto;
import io.github.bjconlan.ibkr.proto.ExecutionRequestProto;
import io.github.bjconlan.ibkr.proto.HistoricalDataRequestProto;
import io.github.bjconlan.ibkr.proto.IdsRequestProto;
import io.github.bjconlan.ibkr.proto.MarketDataRequestProto;
import io.github.bjconlan.ibkr.proto.MarketDataTypeRequestProto;
import io.github.bjconlan.ibkr.proto.OpenOrdersRequestProto;
import io.github.bjconlan.ibkr.proto.PlaceOrderRequestProto;
import io.github.bjconlan.ibkr.proto.PositionsRequestProto;
import io.github.bjconlan.ibkr.proto.StartApiRequestProto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Serialises outbound requests. Every method returns a complete frame (or, for
 * {@link #connectHeader()}, the unframed preamble) that can be written straight to the socket.
 */
public final class Encoder {

    private Encoder() {
    }

    /**
     * The handshake written before any framed message: {@code API\0} followed by a
     * length-prefixed version range such as {@code v100..226}.
     */
    public static byte[] connectHeader() {
        byte[] version = ("v" + Wire.MIN_VERSION + ".." + Wire.MAX_VERSION).getBytes(StandardCharsets.US_ASCII);
        ByteBuffer out = ByteBuffer.allocate(Wire.API_PREAMBLE.length + 4 + version.length)
                .order(ByteOrder.BIG_ENDIAN);
        out.put(Wire.API_PREAMBLE);
        out.putInt(version.length);
        out.put(version);
        return out.array();
    }

    public static byte[] startApi(int clientId, String optionalCapabilities) {
        StartApiRequestProto.StartApiRequest.Builder b = StartApiRequestProto.StartApiRequest.newBuilder()
                .setClientId(clientId);
        if (optionalCapabilities != null && !optionalCapabilities.isEmpty()) {
            b.setOptionalCapabilities(optionalCapabilities);
        }
        return Wire.frame(OutgoingId.START_API, b.build());
    }

    public static byte[] reqIds() {
        return Wire.frame(OutgoingId.REQ_IDS, IdsRequestProto.IdsRequest.newBuilder().setNumIds(1).build());
    }

    public static byte[] reqCurrentTime() {
        return Wire.frame(OutgoingId.REQ_CURRENT_TIME, CurrentTimeRequestProto.CurrentTimeRequest.getDefaultInstance());
    }

    public static byte[] reqContractDetails(int reqId, Contract contract) {
        ContractDataRequestProto.ContractDataRequest request = ContractDataRequestProto.ContractDataRequest.newBuilder()
                .setReqId(reqId)
                .setContract(ProtoMapper.toProto(contract))
                .build();
        return Wire.frame(OutgoingId.REQ_CONTRACT_DATA, request);
    }

    public static byte[] cancelContractDetails(int reqId) {
        return Wire.frame(OutgoingId.CANCEL_CONTRACT_DATA,
                CancelContractDataProto.CancelContractData.newBuilder().setReqId(reqId).build());
    }

    public static byte[] reqMktData(int reqId, Contract contract, String genericTickList,
                                    boolean snapshot, boolean regulatorySnapshot) {
        MarketDataRequestProto.MarketDataRequest.Builder b = MarketDataRequestProto.MarketDataRequest.newBuilder()
                .setReqId(reqId)
                .setContract(ProtoMapper.toProto(contract));
        if (genericTickList != null && !genericTickList.isEmpty()) {
            b.setGenericTickList(genericTickList);
        }
        if (snapshot) {
            b.setSnapshot(true);
        }
        if (regulatorySnapshot) {
            b.setRegulatorySnapshot(true);
        }
        return Wire.frame(OutgoingId.REQ_MKT_DATA, b.build());
    }

    public static byte[] cancelMktData(int reqId) {
        return Wire.frame(OutgoingId.CANCEL_MKT_DATA,
                CancelMarketDataProto.CancelMarketData.newBuilder().setReqId(reqId).build());
    }

    public static byte[] reqHistoricalData(int reqId, Contract contract, String endDateTime,
                                           String barSizeSetting, String duration, boolean useRTH,
                                           String whatToShow, int formatDate, boolean keepUpToDate) {
        HistoricalDataRequestProto.HistoricalDataRequest request = HistoricalDataRequestProto.HistoricalDataRequest.newBuilder()
                .setReqId(reqId)
                .setContract(ProtoMapper.toProto(contract))
                .setEndDateTime(endDateTime == null ? "" : endDateTime)
                .setBarSizeSetting(barSizeSetting)
                .setDuration(duration)
                .setUseRTH(useRTH)
                .setWhatToShow(whatToShow)
                .setFormatDate(formatDate)
                .setKeepUpToDate(keepUpToDate)
                .build();
        return Wire.frame(OutgoingId.REQ_HISTORICAL_DATA, request);
    }

    public static byte[] cancelHistoricalData(int reqId) {
        return Wire.frame(OutgoingId.CANCEL_HISTORICAL_DATA,
                CancelHistoricalDataProto.CancelHistoricalData.newBuilder().setReqId(reqId).build());
    }

    public static byte[] placeOrder(int orderId, Contract contract, Order order) {
        PlaceOrderRequestProto.PlaceOrderRequest request = PlaceOrderRequestProto.PlaceOrderRequest.newBuilder()
                .setOrderId(orderId)
                .setContract(ProtoMapper.toProto(contract))
                .setOrder(ProtoMapper.toProto(order))
                .build();
        return Wire.frame(OutgoingId.PLACE_ORDER, request);
    }

    public static byte[] cancelOrder(int orderId) {
        return Wire.frame(OutgoingId.CANCEL_ORDER,
                CancelOrderRequestProto.CancelOrderRequest.newBuilder().setOrderId(orderId).build());
    }

    public static byte[] reqOpenOrders() {
        return Wire.frame(OutgoingId.REQ_OPEN_ORDERS,
                OpenOrdersRequestProto.OpenOrdersRequest.getDefaultInstance());
    }

    public static byte[] reqPositions() {
        return Wire.frame(OutgoingId.REQ_POSITIONS, PositionsRequestProto.PositionsRequest.getDefaultInstance());
    }

    public static byte[] cancelPositions() {
        return Wire.frame(OutgoingId.CANCEL_POSITIONS, CancelPositionsProto.CancelPositions.getDefaultInstance());
    }

    public static byte[] reqExecutions(int reqId, ExecutionFilter filter) {
        ExecutionRequestProto.ExecutionRequest request = ExecutionRequestProto.ExecutionRequest.newBuilder()
                .setReqId(reqId)
                .setExecutionFilter(ProtoMapper.toProto(filter))
                .build();
        return Wire.frame(OutgoingId.REQ_EXECUTIONS, request);
    }

    public static byte[] reqAccountUpdates(boolean subscribe, String accountCode) {
        AccountDataRequestProto.AccountDataRequest.Builder b = AccountDataRequestProto.AccountDataRequest.newBuilder()
                .setSubscribe(subscribe);
        if (accountCode != null && !accountCode.isEmpty()) {
            b.setAcctCode(accountCode);
        }
        return Wire.frame(OutgoingId.REQ_ACCOUNT_DATA, b.build());
    }

    public static byte[] setMarketDataType(int marketDataType) {
        return Wire.frame(OutgoingId.REQ_MARKET_DATA_TYPE,
                MarketDataTypeRequestProto.MarketDataTypeRequest.newBuilder()
                        .setMarketDataType(marketDataType)
                        .build());
    }
}
