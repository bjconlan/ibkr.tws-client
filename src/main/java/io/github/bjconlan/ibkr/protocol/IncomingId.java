package io.github.bjconlan.ibkr.protocol;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Base ids of the inbound messages this client decodes. Values are stable protocol
 * constants from the TWS API; the protobuf wire id is {@code id + 200}.
 *
 * <p>Mirrors the upstream {@code IncomingMsgId} enum and its protobuf switch. The ids with
 * no protobuf variant ({@code END_CONN}, {@code TICK_EFP}, {@code DELTA_NEUTRAL_VALIDATION},
 * and the two verify-and-auth messages) are intentionally absent: this client speaks the
 * protobuf protocol only.
 */
public enum IncomingId {
    TICK_PRICE(1),  // TickPriceProto.TickPrice
    TICK_SIZE(2),  // TickSizeProto.TickSize
    ORDER_STATUS(3),  // OrderStatusProto.OrderStatus
    ERR_MSG(4),  // ErrorMessageProto.ErrorMessage
    OPEN_ORDER(5),  // OpenOrderProto.OpenOrder
    ACCT_VALUE(6),  // AccountValueProto.AccountValue
    PORTFOLIO_VALUE(7),  // PortfolioValueProto.PortfolioValue
    ACCT_UPDATE_TIME(8),  // AccountUpdateTimeProto.AccountUpdateTime
    NEXT_VALID_ID(9),  // NextValidIdProto.NextValidId
    CONTRACT_DATA(10),  // ContractDataProto.ContractData
    EXECUTION_DATA(11),  // ExecutionDetailsProto.ExecutionDetails
    MARKET_DEPTH(12),  // MarketDepthProto.MarketDepth
    NEWS_BULLETINS(14),  // NewsBulletinProto.NewsBulletin
    MANAGED_ACCTS(15),  // ManagedAccountsProto.ManagedAccounts
    RECEIVE_FA(16),  // ReceiveFAProto.ReceiveFA
    HISTORICAL_DATA(17),  // HistoricalDataProto.HistoricalData
    BOND_CONTRACT_DATA(18),  // ContractDataProto.ContractData
    SCANNER_PARAMETERS(19),  // ScannerParametersProto.ScannerParameters
    SCANNER_DATA(20),  // ScannerDataProto.ScannerData
    TICK_OPTION_COMPUTATION(21),  // TickOptionComputationProto.TickOptionComputation
    TICK_GENERIC(45),  // TickGenericProto.TickGeneric
    TICK_STRING(46),  // TickStringProto.TickString
    CURRENT_TIME(49),  // CurrentTimeProto.CurrentTime
    REAL_TIME_BARS(50),  // RealTimeBarTickProto.RealTimeBarTick
    CONTRACT_DATA_END(52),  // ContractDataEndProto.ContractDataEnd
    OPEN_ORDER_END(53),  // OpenOrdersEndProto.OpenOrdersEnd
    ACCT_DOWNLOAD_END(54),  // AccountDataEndProto.AccountDataEnd
    EXECUTION_DATA_END(55),  // ExecutionDetailsEndProto.ExecutionDetailsEnd
    TICK_SNAPSHOT_END(57),  // TickSnapshotEndProto.TickSnapshotEnd
    MARKET_DATA_TYPE(58),  // MarketDataTypeProto.MarketDataType
    COMMISSION_AND_FEES_REPORT(59),  // CommissionAndFeesReportProto.CommissionAndFeesReport
    POSITION(61),  // PositionProto.Position
    POSITION_END(62),  // PositionEndProto.PositionEnd
    ACCOUNT_SUMMARY(63),  // AccountSummaryProto.AccountSummary
    ACCOUNT_SUMMARY_END(64),  // AccountSummaryEndProto.AccountSummaryEnd
    VERIFY_MESSAGE_API(65),  // VerifyMessageApiProto.VerifyMessageApi
    VERIFY_COMPLETED(66),  // VerifyCompletedProto.VerifyCompleted
    DISPLAY_GROUP_LIST(67),  // DisplayGroupListProto.DisplayGroupList
    DISPLAY_GROUP_UPDATED(68),  // DisplayGroupUpdatedProto.DisplayGroupUpdated
    POSITION_MULTI(71),  // PositionMultiProto.PositionMulti
    POSITION_MULTI_END(72),  // PositionMultiEndProto.PositionMultiEnd
    ACCOUNT_UPDATE_MULTI(73),  // AccountUpdateMultiProto.AccountUpdateMulti
    ACCOUNT_UPDATE_MULTI_END(74),  // AccountUpdateMultiEndProto.AccountUpdateMultiEnd
    SECURITY_DEFINITION_OPTION_PARAMETER(75),  // SecDefOptParameterProto.SecDefOptParameter
    SECURITY_DEFINITION_OPTION_PARAMETER_END(76),  // SecDefOptParameterEndProto.SecDefOptParameterEnd
    SOFT_DOLLAR_TIERS(77),  // SoftDollarTiersProto.SoftDollarTiers
    FAMILY_CODES(78),  // FamilyCodesProto.FamilyCodes
    SYMBOL_SAMPLES(79),  // SymbolSamplesProto.SymbolSamples
    MKT_DEPTH_EXCHANGES(80),  // MarketDepthExchangesProto.MarketDepthExchanges
    TICK_REQ_PARAMS(81),  // TickReqParamsProto.TickReqParams
    SMART_COMPONENTS(82),  // SmartComponentsProto.SmartComponents
    NEWS_ARTICLE(83),  // NewsArticleProto.NewsArticle
    TICK_NEWS(84),  // TickNewsProto.TickNews
    NEWS_PROVIDERS(85),  // NewsProvidersProto.NewsProviders
    HISTORICAL_NEWS(86),  // HistoricalNewsProto.HistoricalNews
    HISTORICAL_NEWS_END(87),  // HistoricalNewsEndProto.HistoricalNewsEnd
    HEAD_TIMESTAMP(88),  // HeadTimestampProto.HeadTimestamp
    HISTOGRAM_DATA(89),  // HistogramDataProto.HistogramData
    HISTORICAL_DATA_UPDATE(90),  // HistoricalDataUpdateProto.HistoricalDataUpdate
    REROUTE_MKT_DATA_REQ(91),  // RerouteMarketDataRequestProto.RerouteMarketDataRequest
    REROUTE_MKT_DEPTH_REQ(92),  // RerouteMarketDepthRequestProto.RerouteMarketDepthRequest
    MARKET_RULE(93),  // MarketRuleProto.MarketRule
    PNL(94),  // PnLProto.PnL
    PNL_SINGLE(95),  // PnLSingleProto.PnLSingle
    HISTORICAL_TICKS(96),  // HistoricalTicksProto.HistoricalTicks
    HISTORICAL_TICKS_BID_ASK(97),  // HistoricalTicksBidAskProto.HistoricalTicksBidAsk
    HISTORICAL_TICKS_LAST(98),  // HistoricalTicksLastProto.HistoricalTicksLast
    TICK_BY_TICK(99),  // TickByTickDataProto.TickByTickData
    ORDER_BOUND(100),  // OrderBoundProto.OrderBound
    COMPLETED_ORDER(101),  // CompletedOrderProto.CompletedOrder
    COMPLETED_ORDERS_END(102),  // CompletedOrdersEndProto.CompletedOrdersEnd
    REPLACE_FA_END(103),  // ReplaceFAEndProto.ReplaceFAEnd
    WSH_META_DATA(104),  // WshMetaDataProto.WshMetaData
    WSH_EVENT_DATA(105),  // WshEventDataProto.WshEventData
    HISTORICAL_SCHEDULE(106),  // HistoricalScheduleProto.HistoricalSchedule
    USER_INFO(107),  // UserInfoProto.UserInfo
    HISTORICAL_DATA_END(108),  // HistoricalDataEndProto.HistoricalDataEnd
    CURRENT_TIME_IN_MILLIS(109),  // CurrentTimeInMillisProto.CurrentTimeInMillis
    CONFIG_RESPONSE(110),  // ConfigResponseProto.ConfigResponse
    UPDATE_CONFIG_RESPONSE(111);  // UpdateConfigResponseProto.UpdateConfigResponse

    private final int id;

    private static final Map<Integer, IncomingId> BY_ID =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(IncomingId::id, Function.identity()));

    IncomingId(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static IncomingId fromId(int id) {
        return BY_ID.get(id);
    }
}
