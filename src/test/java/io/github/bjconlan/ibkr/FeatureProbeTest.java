package io.github.bjconlan.ibkr;

import io.github.bjconlan.ibkr.event.IbEvent;
import io.github.bjconlan.ibkr.protocol.OutgoingId;
import io.github.bjconlan.ibkr.proto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Best-effort sweep of the feature requests against a running gateway, to see which respond on a
 * paper account and which are blocked by entitlements. Read-only: every subscription is cancelled.
 *
 * <pre>{@code
 * IBKR_FEATURES=true IBKR_GATEWAY_PORT=4002 mvn test -Dtest=FeatureProbeTest
 * }</pre>
 *
 * <p>On a paper account with no market data subscription, the reference, account and historical
 * family all answer: matching symbols, market rule, depth exchanges, family codes, soft dollar
 * tiers, sec-def opt params, news providers, historical news and news article, scanner
 * parameters/data, symbol samples, user info, current time in millis, display groups,
 * positions/account-updates multi, head timestamp, histogram, and historical ticks (last, bid-ask
 * and midpoint). The market data family is refused: depth, tick-by-tick and real-time bars need a
 * subscription, {@code reqSmartComponents} needs a {@code bboExchange} that TWS only supplies via
 * {@code TickReqParams} on a live feed, and WSH gets no response on this account.
 *
 * <p>The historical family is additionally refused with "Trading TWS session is connected from a
 * different IP address" while the paper account is logged in elsewhere; that is an account-level
 * competing-session restriction, not a client issue, and clears once the other session ends.
 */
@EnabledIfEnvironmentVariable(named = "IBKR_FEATURES", matches = "true")
class FeatureProbeTest {

    @Test
    void sweepFeatureRequests() throws Exception {
        BlockingQueue<IbEvent> events = new LinkedBlockingQueue<>();
        Map<String, Integer> counts = new TreeMap<>();
        Set<String> errors = new TreeSet<>();
        java.util.concurrent.atomic.AtomicReference<HistoricalNewsProto.HistoricalNews> firstNews =
                new java.util.concurrent.atomic.AtomicReference<>();

        TwsConfig config = new TwsConfig(env("IBKR_GATEWAY_HOST", "127.0.0.1"),
                Integer.parseInt(env("IBKR_GATEWAY_PORT", "4002")),
                Integer.parseInt(env("IBKR_CLIENT_ID", "90")), "",
                Duration.ofSeconds(10), 2, Duration.ofMillis(500), 100, null);

        try (TwsConnection client = TwsConnection.open(config, events::add)) {
            ManagedAccountsProto.ManagedAccounts accounts = await(events, ManagedAccountsProto.ManagedAccounts.class, 10);
            String account = accounts.getAccountsList().split(",")[0];

            client.reqContractDetails(1, aapl());
            ContractDataProto.ContractData details = await(events, ContractDataProto.ContractData.class, 30);
            await(events, ContractDataEndProto.ContractDataEnd.class, 30);
            int conId = details.getContract().getConId();
            int marketRuleId = firstRuleId(details.getContractDetails().getMarketRuleIds());
            System.out.printf("AAPL conId=%d marketRuleId=%d%n", conId, marketRuleId);

            // ---- reference data (no market data entitlement needed)
            client.send(OutgoingId.REQ_MATCHING_SYMBOLS, MatchingSymbolsRequestProto.MatchingSymbolsRequest.newBuilder()
                    .setReqId(10).setPattern("AAPL").build());
            client.send(OutgoingId.REQ_MARKET_RULE, MarketRuleRequestProto.MarketRuleRequest.newBuilder()
                    .setMarketRuleId(marketRuleId).build());
            client.send(OutgoingId.REQ_MKT_DEPTH_EXCHANGES,
                    MarketDepthExchangesRequestProto.MarketDepthExchangesRequest.getDefaultInstance());
            client.send(OutgoingId.REQ_FAMILY_CODES, FamilyCodesRequestProto.FamilyCodesRequest.getDefaultInstance());
            client.send(OutgoingId.REQ_SOFT_DOLLAR_TIERS, SoftDollarTiersRequestProto.SoftDollarTiersRequest.newBuilder()
                    .setReqId(11).build());
            client.send(OutgoingId.REQ_SMART_COMPONENTS, SmartComponentsRequestProto.SmartComponentsRequest.newBuilder()
                    .setReqId(24).setBboExchange("ISLAND").build());
            client.send(OutgoingId.REQ_SEC_DEF_OPT_PARAMS, SecDefOptParamsRequestProto.SecDefOptParamsRequest.newBuilder()
                    .setReqId(12).setUnderlyingSymbol("AAPL").setFutFopExchange("")
                    .setUnderlyingSecType("STK").setUnderlyingConId(conId).build());
            client.send(OutgoingId.REQ_NEWS_PROVIDERS,
                    NewsProvidersRequestProto.NewsProvidersRequest.getDefaultInstance());
            client.send(OutgoingId.REQ_CURRENT_TIME_IN_MILLIS,
                    CurrentTimeInMillisRequestProto.CurrentTimeInMillisRequest.getDefaultInstance());
            client.send(OutgoingId.REQ_USER_INFO, UserInfoRequestProto.UserInfoRequest.newBuilder().setReqId(13).build());
            client.send(OutgoingId.REQ_SCANNER_PARAMETERS,
                    ScannerParametersRequestProto.ScannerParametersRequest.getDefaultInstance());
            client.send(OutgoingId.QUERY_DISPLAY_GROUPS,
                    QueryDisplayGroupsRequestProto.QueryDisplayGroupsRequest.newBuilder().setReqId(17).build());
            client.send(OutgoingId.REQ_PNL_SINGLE, PnLSingleRequestProto.PnLSingleRequest.newBuilder()
                    .setReqId(18).setAccount(account).setConId(conId).build());
            client.send(OutgoingId.REQ_POSITIONS_MULTI, PositionsMultiRequestProto.PositionsMultiRequest.newBuilder()
                    .setReqId(19).setAccount(account).build());
            client.send(OutgoingId.REQ_ACCOUNT_UPDATES_MULTI,
                    AccountUpdatesMultiRequestProto.AccountUpdatesMultiRequest.newBuilder()
                            .setReqId(20).setAccount(account).setLedgerAndNLV(true).build());
            client.send(OutgoingId.REQ_HISTORICAL_NEWS, HistoricalNewsRequestProto.HistoricalNewsRequest.newBuilder()
                    .setReqId(21).setConId(conId).setProviderCodes("BRFG")
                    .setStartDateTime("20260907-00:00:00").setEndDateTime("20260914-00:00:00")
                    .setTotalResults(5).build());
            client.send(OutgoingId.REQ_WSH_META_DATA, WshMetaDataRequestProto.WshMetaDataRequest.newBuilder()
                    .setReqId(22).build());
            client.send(OutgoingId.REQ_WSH_EVENT_DATA, WshEventDataRequestProto.WshEventDataRequest.newBuilder()
                    .setReqId(23).setConId(conId).setTotalLimit(5).build());

            // ---- market data features (entitlement dependent)
            client.send(OutgoingId.REQ_MKT_DEPTH, MarketDepthRequestProto.MarketDepthRequest.newBuilder()
                    .setReqId(30).setContract(aapl()).setNumRows(5).setIsSmartDepth(true).build());
            client.send(OutgoingId.REQ_TICK_BY_TICK_DATA, TickByTickRequestProto.TickByTickRequest.newBuilder()
                    .setReqId(31).setContract(aapl()).setTickType("Last").setNumberOfTicks(5).build());
            client.send(OutgoingId.REQ_HISTORICAL_TICKS, HistoricalTicksRequestProto.HistoricalTicksRequest.newBuilder()
                    .setReqId(32).setContract(aapl())
                    .setStartDateTime("20260913-00:00:00").setEndDateTime("20260914-00:00:00")
                    .setNumberOfTicks(5).setWhatToShow("TRADES").setUseRTH(true).build());
            client.send(OutgoingId.REQ_HISTORICAL_TICKS, HistoricalTicksRequestProto.HistoricalTicksRequest.newBuilder()
                    .setReqId(40).setContract(aapl())
                    .setStartDateTime("20260913-00:00:00").setEndDateTime("20260914-00:00:00")
                    .setNumberOfTicks(5).setWhatToShow("BID_ASK").setUseRTH(true).build());
            client.send(OutgoingId.REQ_HISTORICAL_TICKS, HistoricalTicksRequestProto.HistoricalTicksRequest.newBuilder()
                    .setReqId(41).setContract(aapl())
                    .setStartDateTime("20260913-00:00:00").setEndDateTime("20260914-00:00:00")
                    .setNumberOfTicks(5).setWhatToShow("MIDPOINT").setUseRTH(true).build());
            client.send(OutgoingId.REQ_HEAD_TIMESTAMP, HeadTimestampRequestProto.HeadTimestampRequest.newBuilder()
                    .setReqId(33).setContract(aapl()).setUseRTH(true).setWhatToShow("TRADES").setFormatDate(1).build());
            client.send(OutgoingId.REQ_HISTOGRAM_DATA, HistogramDataRequestProto.HistogramDataRequest.newBuilder()
                    .setReqId(34).setContract(aapl()).setUseRTH(true).setTimePeriod("1 day").build());
            client.send(OutgoingId.REQ_REAL_TIME_BARS, RealTimeBarsRequestProto.RealTimeBarsRequest.newBuilder()
                    .setReqId(35).setContract(aapl()).setBarSize(5).setWhatToShow("TRADES").setUseRTH(true).build());
            client.send(OutgoingId.REQ_SCANNER_SUBSCRIPTION,
                    ScannerSubscriptionRequestProto.ScannerSubscriptionRequest.newBuilder()
                            .setReqId(36)
                            .setScannerSubscription(ScannerSubscriptionProto.ScannerSubscription.newBuilder()
                                    .setNumberOfRows(5).setInstrument("STK").setLocationCode("STK.US.MAJOR")
                                    .setScanCode("TOP_PERC_GAIN").build())
                            .build());

            drain(events, Duration.ofSeconds(25), counts, errors, firstNews);

            // Exercise the news-article decoder using an id from the historical news response.
            HistoricalNewsProto.HistoricalNews news = firstNews.get();
            if (news != null) {
                System.out.printf("news article %s from %s%n", news.getArticleId(), news.getProviderCode());
                client.send(OutgoingId.REQ_NEWS_ARTICLE, NewsArticleRequestProto.NewsArticleRequest.newBuilder()
                        .setReqId(25).setProviderCode(news.getProviderCode()).setArticleId(news.getArticleId()).build());
            }

            // ---- cancel every subscription
            client.send(OutgoingId.CANCEL_MKT_DEPTH, CancelMarketDepthProto.CancelMarketDepth.newBuilder()
                    .setReqId(30).setIsSmartDepth(true).build());
            client.send(OutgoingId.CANCEL_TICK_BY_TICK_DATA,
                    CancelTickByTickProto.CancelTickByTick.newBuilder().setReqId(31).build());
            client.send(OutgoingId.CANCEL_HISTORICAL_TICKS,
                    CancelHistoricalTicksProto.CancelHistoricalTicks.newBuilder().setReqId(32).build());
            client.send(OutgoingId.CANCEL_HEAD_TIMESTAMP,
                    CancelHeadTimestampProto.CancelHeadTimestamp.newBuilder().setReqId(33).build());
            client.send(OutgoingId.CANCEL_HISTOGRAM_DATA,
                    CancelHistogramDataProto.CancelHistogramData.newBuilder().setReqId(34).build());
            client.send(OutgoingId.CANCEL_REAL_TIME_BARS,
                    CancelRealTimeBarsProto.CancelRealTimeBars.newBuilder().setReqId(35).build());
            client.send(OutgoingId.CANCEL_SCANNER_SUBSCRIPTION,
                    CancelScannerSubscriptionProto.CancelScannerSubscription.newBuilder().setReqId(36).build());
            client.send(OutgoingId.CANCEL_PNL_SINGLE,
                    CancelPnLSingleProto.CancelPnLSingle.newBuilder().setReqId(18).build());
            client.send(OutgoingId.CANCEL_POSITIONS_MULTI,
                    CancelPositionsMultiProto.CancelPositionsMulti.newBuilder().setReqId(19).build());
            client.send(OutgoingId.CANCEL_ACCOUNT_UPDATES_MULTI,
                    CancelAccountUpdatesMultiProto.CancelAccountUpdatesMulti.newBuilder().setReqId(20).build());
            client.send(OutgoingId.CANCEL_WSH_META_DATA,
                    CancelWshMetaDataProto.CancelWshMetaData.newBuilder().setReqId(22).build());
            client.send(OutgoingId.CANCEL_WSH_EVENT_DATA,
                    CancelWshEventDataProto.CancelWshEventData.newBuilder().setReqId(23).build());
            drain(events, Duration.ofSeconds(5), counts, errors, firstNews);
        }

        System.out.println("payloads: " + counts);
        System.out.println("errors:");
        errors.forEach(e -> System.out.println("  " + e));

        // Reference data that does not need a market data subscription.
        assertTrue(counts.containsKey("MarketRule"), "market rule not returned");
        assertTrue(counts.containsKey("MarketDepthExchanges"), "depth exchanges not returned");
        assertTrue(counts.containsKey("CurrentTimeInMillis"), "current time in millis not returned");
    }

    private static ContractProto.Contract aapl() {
        return ContractProto.Contract.newBuilder()
                .setSymbol("AAPL").setSecType("STK").setExchange("SMART")
                .setPrimaryExch("NASDAQ").setCurrency("USD").build();
    }

    private static int firstRuleId(String ids) {
        if (ids == null || ids.isBlank()) {
            return 4563;
        }
        return Integer.parseInt(ids.split(",")[0]);
    }

    private static void drain(BlockingQueue<IbEvent> events, Duration window,
                              Map<String, Integer> counts, Set<String> errors,
                              java.util.concurrent.atomic.AtomicReference<HistoricalNewsProto.HistoricalNews> firstNews)
            throws InterruptedException {
        long deadline = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < deadline) {
            IbEvent event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (event == null) {
                return;
            }
            if (event instanceof IbEvent.Message m) {
                counts.merge(m.payload().getClass().getSimpleName(), 1, Integer::sum);
                if (m.payload() instanceof HistoricalNewsProto.HistoricalNews n) {
                    firstNews.compareAndSet(null, n);
                }
                if (m.payload() instanceof ErrorMessageProto.ErrorMessage e) {
                    errors.add("%d: %s".formatted(e.getErrorCode(), e.getErrorMsg()));
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
