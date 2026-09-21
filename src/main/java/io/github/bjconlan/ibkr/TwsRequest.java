package io.github.bjconlan.ibkr;

import com.google.protobuf.MessageLite;
import io.github.bjconlan.ibkr.event.IbEvent;
import io.github.bjconlan.ibkr.pacing.PacingKeys;
import io.github.bjconlan.ibkr.protocol.IncomingId;
import io.github.bjconlan.ibkr.protocol.OutgoingId;
import io.github.bjconlan.ibkr.proto.CancelContractDataProto;
import io.github.bjconlan.ibkr.proto.CancelHistoricalDataProto;
import io.github.bjconlan.ibkr.proto.CancelMarketDataProto;
import io.github.bjconlan.ibkr.proto.ContractDataEndProto;
import io.github.bjconlan.ibkr.proto.ContractDataRequestProto;
import io.github.bjconlan.ibkr.proto.ContractDataProto;
import io.github.bjconlan.ibkr.proto.ContractProto;
import io.github.bjconlan.ibkr.proto.ExecutionDetailsEndProto;
import io.github.bjconlan.ibkr.proto.ExecutionDetailsProto;
import io.github.bjconlan.ibkr.proto.ExecutionFilterProto;
import io.github.bjconlan.ibkr.proto.ExecutionRequestProto;
import io.github.bjconlan.ibkr.proto.HistoricalDataEndProto;
import io.github.bjconlan.ibkr.proto.HistoricalDataProto;
import io.github.bjconlan.ibkr.proto.HistoricalDataRequestProto;
import io.github.bjconlan.ibkr.proto.HistoricalDataUpdateProto;
import io.github.bjconlan.ibkr.proto.MarketDataRequestProto;
import io.github.bjconlan.ibkr.proto.TickGenericProto;
import io.github.bjconlan.ibkr.proto.TickOptionComputationProto;
import io.github.bjconlan.ibkr.proto.TickPriceProto;
import io.github.bjconlan.ibkr.proto.TickReqParamsProto;
import io.github.bjconlan.ibkr.proto.TickSizeProto;
import io.github.bjconlan.ibkr.proto.TickSnapshotEndProto;
import io.github.bjconlan.ibkr.proto.TickStringProto;

import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Describes one outbound request and how its responses are identified, independent of any
 * connection or request id.
 *
 * <p>{@link TwsSession} allocates the {@code reqId}, builds the protobuf through
 * {@link #encode(int)}, sends it, then uses {@link #responses()}, {@link #correlate} and
 * {@link #terminal} to route responses back. A {@code null} {@link #terminal} means the response
 * is open-ended (a subscription) and only ends when the handle is closed or the connection drops;
 * a {@code null} {@link #cancelId}/{@link #cancel} means there is nothing to cancel.
 *
 * <p>Instances are immutable data. Build them with the static factories.
 */
public record TwsRequest(
        OutgoingId id,
        IntFunction<MessageLite> encode,
        PacingKeys pacing,
        Set<IncomingId> responses,
        ToIntFunction<IbEvent.Message> correlate,
        Predicate<IbEvent.Message> terminal,
        IntFunction<MessageLite> cancel,
        OutgoingId cancelId,
        boolean lineLease) {

    public TwsRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(encode, "encode");
        Objects.requireNonNull(correlate, "correlate");
        Objects.requireNonNull(responses, "responses");
        pacing = pacing == null ? PacingKeys.NONE : pacing;
        responses = Set.copyOf(responses);
    }

    /** Whether this request terminates on its own; open-ended requests need {@code stream}/{@code on}. */
    public boolean isBounded() {
        return terminal != null;
    }

    // ------------------------------------------------------------------ factories

    /** Contract details for one instrument. Bounded: terminates on {@code ContractDataEnd}. */
    public static TwsRequest contractDetails(ContractProto.Contract contract) {
        return new TwsRequest(
                OutgoingId.REQ_CONTRACT_DATA,
                reqId -> ContractDataRequestProto.ContractDataRequest.newBuilder()
                        .setReqId(reqId).setContract(contract).build(),
                PacingKeys.NONE,
                Set.of(IncomingId.CONTRACT_DATA, IncomingId.CONTRACT_DATA_END),
                m -> switch (m.payload()) {
                    case ContractDataProto.ContractData d -> d.getReqId();
                    case ContractDataEndProto.ContractDataEnd e -> e.getReqId();
                    default -> -1;
                },
                m -> m.payload() instanceof ContractDataEndProto.ContractDataEnd,
                reqId -> CancelContractDataProto.CancelContractData.newBuilder().setReqId(reqId).build(),
                OutgoingId.CANCEL_CONTRACT_DATA,
                false);
    }

    /**
     * Historical bars. Parameter order mirrors the upstream {@code EClient}:
     * {@code endDateTime, duration, barSizeSetting, whatToShow, useRTH, formatDate, keepUpToDate}.
     *
     * <p>When {@code keepUpToDate} is set the response never ends and must be cancelled, so the
     * request is open-ended.
     */
    public static TwsRequest historicalData(ContractProto.Contract contract, String endDateTime,
                                            String duration, String barSizeSetting, String whatToShow,
                                            boolean useRTH, int formatDate, boolean keepUpToDate) {
        String contractScope = "%s|%s".formatted(fingerprint(contract), whatToShow);
        String request = "%s|%s|%s|%s|%b|%d".formatted(
                contractScope, endDateTime, barSizeSetting, duration, useRTH, formatDate);
        return new TwsRequest(
                OutgoingId.REQ_HISTORICAL_DATA,
                reqId -> HistoricalDataRequestProto.HistoricalDataRequest.newBuilder()
                        .setReqId(reqId)
                        .setContract(contract)
                        .setEndDateTime(endDateTime == null ? "" : endDateTime)
                        .setBarSizeSetting(barSizeSetting)
                        .setDuration(duration)
                        .setUseRTH(useRTH)
                        .setWhatToShow(whatToShow)
                        .setFormatDate(formatDate)
                        .setKeepUpToDate(keepUpToDate)
                        .build(),
                new PacingKeys(request, contractScope),
                Set.of(IncomingId.HISTORICAL_DATA, IncomingId.HISTORICAL_DATA_UPDATE,
                        IncomingId.HISTORICAL_DATA_END),
                m -> switch (m.payload()) {
                    case HistoricalDataProto.HistoricalData h -> h.getReqId();
                    case HistoricalDataUpdateProto.HistoricalDataUpdate u -> u.getReqId();
                    case HistoricalDataEndProto.HistoricalDataEnd e -> e.getReqId();
                    default -> -1;
                },
                keepUpToDate ? null : m -> m.payload() instanceof HistoricalDataEndProto.HistoricalDataEnd,
                reqId -> CancelHistoricalDataProto.CancelHistoricalData.newBuilder().setReqId(reqId).build(),
                OutgoingId.CANCEL_HISTORICAL_DATA,
                false);
    }

    /**
     * Top-of-book market data. A snapshot terminates on {@code TickSnapshotEnd}; a streaming
     * subscription is open-ended and holds a market data line permit until closed.
     */
    public static TwsRequest marketData(ContractProto.Contract contract, String genericTickList,
                                        boolean snapshot, boolean regulatorySnapshot) {
        return new TwsRequest(
                OutgoingId.REQ_MKT_DATA,
                reqId -> {
                    MarketDataRequestProto.MarketDataRequest.Builder b =
                            MarketDataRequestProto.MarketDataRequest.newBuilder()
                                    .setReqId(reqId).setContract(contract);
                    if (genericTickList != null && !genericTickList.isEmpty()) {
                        b.setGenericTickList(genericTickList);
                    }
                    if (snapshot) {
                        b.setSnapshot(true);
                    }
                    if (regulatorySnapshot) {
                        b.setRegulatorySnapshot(true);
                    }
                    return b.build();
                },
                PacingKeys.NONE,
                Set.of(IncomingId.TICK_PRICE, IncomingId.TICK_SIZE, IncomingId.TICK_GENERIC,
                        IncomingId.TICK_STRING, IncomingId.TICK_OPTION_COMPUTATION,
                        IncomingId.TICK_SNAPSHOT_END, IncomingId.TICK_REQ_PARAMS),
                m -> switch (m.payload()) {
                    case TickPriceProto.TickPrice p -> p.getReqId();
                    case TickSizeProto.TickSize s -> s.getReqId();
                    case TickGenericProto.TickGeneric g -> g.getReqId();
                    case TickStringProto.TickString s -> s.getReqId();
                    case TickOptionComputationProto.TickOptionComputation o -> o.getReqId();
                    case TickSnapshotEndProto.TickSnapshotEnd e -> e.getReqId();
                    case TickReqParamsProto.TickReqParams t -> t.getReqId();
                    default -> -1;
                },
                snapshot ? m -> m.payload() instanceof TickSnapshotEndProto.TickSnapshotEnd : null,
                reqId -> CancelMarketDataProto.CancelMarketData.newBuilder().setReqId(reqId).build(),
                OutgoingId.CANCEL_MKT_DATA,
                true);
    }

    /**
     * Execution details for the given filter. Bounded: terminates on {@code ExecutionDetailsEnd}.
     * A {@code null} filter is treated as the empty filter.
     */
    public static TwsRequest executions(ExecutionFilterProto.ExecutionFilter filter) {
        ExecutionFilterProto.ExecutionFilter effective =
                filter == null ? ExecutionFilterProto.ExecutionFilter.getDefaultInstance() : filter;
        return new TwsRequest(
                OutgoingId.REQ_EXECUTIONS,
                reqId -> ExecutionRequestProto.ExecutionRequest.newBuilder()
                        .setReqId(reqId).setExecutionFilter(effective).build(),
                PacingKeys.NONE,
                Set.of(IncomingId.EXECUTION_DATA, IncomingId.EXECUTION_DATA_END),
                m -> switch (m.payload()) {
                    case ExecutionDetailsProto.ExecutionDetails d -> d.getReqId();
                    case ExecutionDetailsEndProto.ExecutionDetailsEnd e -> e.getReqId();
                    default -> -1;
                },
                m -> m.payload() instanceof ExecutionDetailsEndProto.ExecutionDetailsEnd,
                null,
                null,
                false);
    }

    // ------------------------------------------------------------------ internals

    private static String fingerprint(ContractProto.Contract contract) {
        if (contract == null) {
            return "";
        }
        return "%s|%s|%s|%s|%s|%s".formatted(
                contract.getConId(), contract.getSymbol(), contract.getSecType(),
                contract.getExchange(), contract.getCurrency(), contract.getLastTradeDateOrContractMonth());
    }
}
