package io.github.bjconlan.ibkr.protocol;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.github.bjconlan.ibkr.event.IbEvent;
import io.github.bjconlan.ibkr.proto.*;

import java.util.Arrays;
import java.util.List;

/**
 * Turns one framed server message into {@link IbEvent}s.
 *
 * <p>Every protobuf message is carried verbatim as its generated class inside
 * {@link IbEvent.Message}; nothing is remapped into a parallel type. The switch over
 * {@link IncomingId} is exhaustive, so adding an id to the enum without a parser here is a
 * compile error.
 */
public final class Decoder {

    private static final int NO_VALID_ID = -1;
    private static final int UNKNOWN_ID = 505;

    /** Decodes one message body, including its 4-byte id prefix. */
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
            return List.of(new IbEvent.Message(baseId, parse(id, payload)));
        } catch (InvalidProtocolBufferException e) {
            return List.of(error(NO_VALID_ID, UNKNOWN_ID,
                    "malformed %s message: %s".formatted(id, e.getMessage())));
        }
    }

    private static Message parse(IncomingId id, byte[] payload) throws InvalidProtocolBufferException {
        byte[] body = slice(payload);
        return switch (id) {
                case TICK_PRICE -> TickPriceProto.TickPrice.parseFrom(body);
                case TICK_SIZE -> TickSizeProto.TickSize.parseFrom(body);
                case ORDER_STATUS -> OrderStatusProto.OrderStatus.parseFrom(body);
                case ERR_MSG -> ErrorMessageProto.ErrorMessage.parseFrom(body);
                case OPEN_ORDER -> OpenOrderProto.OpenOrder.parseFrom(body);
                case ACCT_VALUE -> AccountValueProto.AccountValue.parseFrom(body);
                case PORTFOLIO_VALUE -> PortfolioValueProto.PortfolioValue.parseFrom(body);
                case ACCT_UPDATE_TIME -> AccountUpdateTimeProto.AccountUpdateTime.parseFrom(body);
                case NEXT_VALID_ID -> NextValidIdProto.NextValidId.parseFrom(body);
                case CONTRACT_DATA -> ContractDataProto.ContractData.parseFrom(body);
                case EXECUTION_DATA -> ExecutionDetailsProto.ExecutionDetails.parseFrom(body);
                case MARKET_DEPTH -> MarketDepthProto.MarketDepth.parseFrom(body);
                case NEWS_BULLETINS -> NewsBulletinProto.NewsBulletin.parseFrom(body);
                case MANAGED_ACCTS -> ManagedAccountsProto.ManagedAccounts.parseFrom(body);
                case RECEIVE_FA -> ReceiveFAProto.ReceiveFA.parseFrom(body);
                case HISTORICAL_DATA -> HistoricalDataProto.HistoricalData.parseFrom(body);
                case BOND_CONTRACT_DATA -> ContractDataProto.ContractData.parseFrom(body);
                case SCANNER_PARAMETERS -> ScannerParametersProto.ScannerParameters.parseFrom(body);
                case SCANNER_DATA -> ScannerDataProto.ScannerData.parseFrom(body);
                case TICK_OPTION_COMPUTATION -> TickOptionComputationProto.TickOptionComputation.parseFrom(body);
                case TICK_GENERIC -> TickGenericProto.TickGeneric.parseFrom(body);
                case TICK_STRING -> TickStringProto.TickString.parseFrom(body);
                case CURRENT_TIME -> CurrentTimeProto.CurrentTime.parseFrom(body);
                case REAL_TIME_BARS -> RealTimeBarTickProto.RealTimeBarTick.parseFrom(body);
                case CONTRACT_DATA_END -> ContractDataEndProto.ContractDataEnd.parseFrom(body);
                case OPEN_ORDER_END -> OpenOrdersEndProto.OpenOrdersEnd.parseFrom(body);
                case ACCT_DOWNLOAD_END -> AccountDataEndProto.AccountDataEnd.parseFrom(body);
                case EXECUTION_DATA_END -> ExecutionDetailsEndProto.ExecutionDetailsEnd.parseFrom(body);
                case TICK_SNAPSHOT_END -> TickSnapshotEndProto.TickSnapshotEnd.parseFrom(body);
                case MARKET_DATA_TYPE -> MarketDataTypeProto.MarketDataType.parseFrom(body);
                case COMMISSION_AND_FEES_REPORT -> CommissionAndFeesReportProto.CommissionAndFeesReport.parseFrom(body);
                case POSITION -> PositionProto.Position.parseFrom(body);
                case POSITION_END -> PositionEndProto.PositionEnd.parseFrom(body);
                case ACCOUNT_SUMMARY -> AccountSummaryProto.AccountSummary.parseFrom(body);
                case ACCOUNT_SUMMARY_END -> AccountSummaryEndProto.AccountSummaryEnd.parseFrom(body);
                case VERIFY_MESSAGE_API -> VerifyMessageApiProto.VerifyMessageApi.parseFrom(body);
                case VERIFY_COMPLETED -> VerifyCompletedProto.VerifyCompleted.parseFrom(body);
                case DISPLAY_GROUP_LIST -> DisplayGroupListProto.DisplayGroupList.parseFrom(body);
                case DISPLAY_GROUP_UPDATED -> DisplayGroupUpdatedProto.DisplayGroupUpdated.parseFrom(body);
                case POSITION_MULTI -> PositionMultiProto.PositionMulti.parseFrom(body);
                case POSITION_MULTI_END -> PositionMultiEndProto.PositionMultiEnd.parseFrom(body);
                case ACCOUNT_UPDATE_MULTI -> AccountUpdateMultiProto.AccountUpdateMulti.parseFrom(body);
                case ACCOUNT_UPDATE_MULTI_END -> AccountUpdateMultiEndProto.AccountUpdateMultiEnd.parseFrom(body);
                case SECURITY_DEFINITION_OPTION_PARAMETER -> SecDefOptParameterProto.SecDefOptParameter.parseFrom(body);
                case SECURITY_DEFINITION_OPTION_PARAMETER_END -> SecDefOptParameterEndProto.SecDefOptParameterEnd.parseFrom(body);
                case SOFT_DOLLAR_TIERS -> SoftDollarTiersProto.SoftDollarTiers.parseFrom(body);
                case FAMILY_CODES -> FamilyCodesProto.FamilyCodes.parseFrom(body);
                case SYMBOL_SAMPLES -> SymbolSamplesProto.SymbolSamples.parseFrom(body);
                case MKT_DEPTH_EXCHANGES -> MarketDepthExchangesProto.MarketDepthExchanges.parseFrom(body);
                case TICK_REQ_PARAMS -> TickReqParamsProto.TickReqParams.parseFrom(body);
                case SMART_COMPONENTS -> SmartComponentsProto.SmartComponents.parseFrom(body);
                case NEWS_ARTICLE -> NewsArticleProto.NewsArticle.parseFrom(body);
                case TICK_NEWS -> TickNewsProto.TickNews.parseFrom(body);
                case NEWS_PROVIDERS -> NewsProvidersProto.NewsProviders.parseFrom(body);
                case HISTORICAL_NEWS -> HistoricalNewsProto.HistoricalNews.parseFrom(body);
                case HISTORICAL_NEWS_END -> HistoricalNewsEndProto.HistoricalNewsEnd.parseFrom(body);
                case HEAD_TIMESTAMP -> HeadTimestampProto.HeadTimestamp.parseFrom(body);
                case HISTOGRAM_DATA -> HistogramDataProto.HistogramData.parseFrom(body);
                case HISTORICAL_DATA_UPDATE -> HistoricalDataUpdateProto.HistoricalDataUpdate.parseFrom(body);
                case REROUTE_MKT_DATA_REQ -> RerouteMarketDataRequestProto.RerouteMarketDataRequest.parseFrom(body);
                case REROUTE_MKT_DEPTH_REQ -> RerouteMarketDepthRequestProto.RerouteMarketDepthRequest.parseFrom(body);
                case MARKET_RULE -> MarketRuleProto.MarketRule.parseFrom(body);
                case PNL -> PnLProto.PnL.parseFrom(body);
                case PNL_SINGLE -> PnLSingleProto.PnLSingle.parseFrom(body);
                case HISTORICAL_TICKS -> HistoricalTicksProto.HistoricalTicks.parseFrom(body);
                case HISTORICAL_TICKS_BID_ASK -> HistoricalTicksBidAskProto.HistoricalTicksBidAsk.parseFrom(body);
                case HISTORICAL_TICKS_LAST -> HistoricalTicksLastProto.HistoricalTicksLast.parseFrom(body);
                case TICK_BY_TICK -> TickByTickDataProto.TickByTickData.parseFrom(body);
                case ORDER_BOUND -> OrderBoundProto.OrderBound.parseFrom(body);
                case COMPLETED_ORDER -> CompletedOrderProto.CompletedOrder.parseFrom(body);
                case COMPLETED_ORDERS_END -> CompletedOrdersEndProto.CompletedOrdersEnd.parseFrom(body);
                case REPLACE_FA_END -> ReplaceFAEndProto.ReplaceFAEnd.parseFrom(body);
                case WSH_META_DATA -> WshMetaDataProto.WshMetaData.parseFrom(body);
                case WSH_EVENT_DATA -> WshEventDataProto.WshEventData.parseFrom(body);
                case HISTORICAL_SCHEDULE -> HistoricalScheduleProto.HistoricalSchedule.parseFrom(body);
                case USER_INFO -> UserInfoProto.UserInfo.parseFrom(body);
                case HISTORICAL_DATA_END -> HistoricalDataEndProto.HistoricalDataEnd.parseFrom(body);
                case CURRENT_TIME_IN_MILLIS -> CurrentTimeInMillisProto.CurrentTimeInMillis.parseFrom(body);
                case CONFIG_RESPONSE -> ConfigResponseProto.ConfigResponse.parseFrom(body);
                case UPDATE_CONFIG_RESPONSE -> UpdateConfigResponseProto.UpdateConfigResponse.parseFrom(body);
        };
    }

    /** Strips the 4-byte message id so the remainder can be parsed as a protobuf body. */
    private static byte[] slice(byte[] payload) {
        return Arrays.copyOfRange(payload, 4, payload.length);
    }

    private static IbEvent error(int requestId, int code, String message) {
        return new IbEvent.Error(requestId, System.currentTimeMillis(), code,
                message == null ? "" : message, null);
    }
}
