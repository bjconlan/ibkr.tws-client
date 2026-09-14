# Verification status

What has been exercised against a real account, what has not, and why. Last updated
2026-09-14.

## Environment used

- Paper IB Gateway, `ghcr.io/gnzsnz/ib-gateway:latest`, `TRADING_MODE=paper`,
  `READ_ONLY_API=no`, account `DU5415404`, server version **226**.
- No market data subscription, so the account serves **delayed** data and rejects the
  entitlement-gated requests below.
- Gateway clock is `Etc/UTC`.
- The historical family is additionally refused with `Trading TWS session is connected from a
  different IP address` while the paper account is logged in elsewhere (another TWS/mobile/web
  session). That is an account-level restriction, not a client issue; it clears when the other
  session ends. Verified: it is not affected by the Docker source IP (host networking made no
  difference).

## Test inventory

| Test | Scope | Opt-in | Command |
|------|-------|--------|---------|
| `EncoderDecoderTest` | codec, framing, handshake | always | `mvn test` |
| `FakeServerIntegrationTest` | full client against a socket-level fake TWS | always | `mvn test` |
| `PacerTest` | rate, keyed and concurrency rules | always | `mvn test` |
| `IbGatewayContainerIT` | starts a paper gateway, connect + contract details + time | credentials + Docker | `mvn verify` |
| `GatewaySmokeTest` | read-only smoke against a running gateway | `IBKR_SMOKE=true` | `mvn test -Dtest=GatewaySmokeTest` |
| `ContractProbeTest` | resolve contracts, print increments and trading hours | `IBKR_PROBE=true` | `mvn test -Dtest=ContractProbeTest` |
| `PaperOrderTest` | order lifecycle: resting cancel, fill + flatten | `IBKR_ORDER=true` | `mvn test -Dtest=PaperOrderTest` |
| `AccountStreamsTest` | account/order streams and end markers | `IBKR_STREAMS=true` | `mvn test -Dtest=AccountStreamsTest` |
| `FeatureProbeTest` | broad feature sweep, reports answered vs refused | `IBKR_FEATURES=true` | `mvn test -Dtest=FeatureProbeTest` |

Start a gateway for the opt-in tests:

```sh
docker run -d --name ibkr-paper --env-file .env \
  -e TRADING_MODE=paper -e READ_ONLY_API=no -e BYPASS_WARNING=yes \
  -p 127.0.0.1:4002:4004 ghcr.io/gnzsnz/ib-gateway:latest
```

## Verified

**Protocol and session**
- `API\0` handshake, server-version negotiation, `Connected`, `Disconnected`, `Error`.
- Exhaustive `IncomingId` decoding: no `unknown message id` (505) was ever observed across all
  probes, so the wiring covers every message the server sent.

**Reference and account data** (paper, no subscription)
- matching symbols, market rule, market depth exchanges, family codes, soft dollar tiers,
  sec-def opt params (39 sets + end), news providers, symbol samples, user info, current time
  in millis, display groups.
- account summary (+tags), account updates, account updates multi, positions multi, PnL.
- scanner parameters and a scanner subscription.

**Historical / news**
- head timestamp, histogram, historical ticks (TRADES, BID_ASK, MIDPOINT), historical news and
  a news article fetched by id.
- historical bars (5-minute, one day) and contract details, current time.

**Orders** (paper, read-write)
- resting limit buy: `Submitted`, echoed by `reqOpenOrders`, cancelled.
- marketable limit buy → `PreSubmitted` → `Filled`; market sell to flatten.
- `ExecutionDetails` and `CommissionAndFeesReport` for both legs; position back to zero.

## Not yet verified

### Entitlement-gated (needs a market data subscription)
- market depth: `updateMktDepth`, `updateMktDepthL2`, `reqMktDepthExchanges` payload content.
- tick-by-tick: `reqTickByTickData` for Last / AllLast / BidAsk / MidPoint, size-only filtering,
  `TickAttribBidAsk` / `TickAttribLast`, `TickNews`.
- real-time bars: `reqRealTimeBars`, `RealTimeBarTick`.
- smart components: `reqSmartComponents` (needs a valid `bboExchange`, normally delivered by
  `TickReqParams` on a live feed).
- live/frozen/regulatory market data types, generic tick lists, streaming tick updates and
  their cancel semantics.

### No response on this account
- WSH: `reqWshMetaData`, `reqWshEventData` (returned nothing and no explicit error).
- Display group updates (`DisplayGroupUpdated`) — requires a subscription call.
- Historical news for providers other than the one that answered; `NewsBulletin` (legacy).

### Orders and instruments
- Order types other than LMT/MKT: STP, STP LMT, TRAIL, TRAIL LIMIT, REL, MOC/LOC, adaptive and
  algo orders, bracket / OCA / attached orders, order conditions, soft-dollar tiers, hidden /
  iceberg, fractional, short sales, combos.
- `whatIf` order preview; order modification/replace; `reqGlobalCancel`; `reqAllOpenOrders`
  across clients; `reqAutoOpenOrders`; `OrderBound`; `reqCompletedOrders(apiOnly=true)`;
  partial fills and rejections; `outsideRth`, `activeStartTime`/`activeStopTime`,
  `autoCancelDate`, GTD/GTC/DAY expiry behaviour.
- Instruments other than FMG/ASX stock and AAPL: FX on IDEALPRO, futures, options, bonds,
  combos, non-US equities, and crypto (the paper account rejects crypto orders with
  `201 Invalid account`, though its contract and market data resolve).

### Account and portfolio
- A non-flat portfolio: `PortfolioValue` with a real position, `PnL` alongside a position,
  `reqPnLSingle` for a held contract, realized PnL accounting.
- FA / model portfolios (`requestFA`, `replaceFA`, `reqPositionsMulti`/`reqAccountUpdatesMulti`
  with a model code), account summary across the full tag set, `ledgerAndNLV` variants.

### Historical matrix
- Bar sizes and duration combinations, `whatToShow` values beyond TRADES/BID_ASK/MIDPOINT
  (ADJUSTED_LAST, ASK, BID, FEE_RATE, HISTORICAL_VOLATILITY, OPTION_IMPLIED_VOLATILITY,
  SCHEDULE, YIELD_*), `formatDate=2` (epoch), `keepUpToDate` (`HistoricalDataUpdate`),
  `reqHistoricalSchedule` (SCHEDULE), historical data for FX/futures.

### Resilience and concurrency
- Reconnect after a dropped socket; multiple clients / client-id conflicts; optional
  capabilities; server versions 201–225 and above 226; TLS (`UseSSL`); malformed or truncated
  frames; `ReadOnlyApi` rejection paths.
- Parallel requests from many virtual threads; cancel/response races; market-data lease
  release on snapshot completion vs disconnect; pacer behaviour under sustained load and when a
  limit is actually breached (including IBKR's error 100 and the three-strike disconnect).

### Legacy and administrative
- `verifyRequest` / `verifyMessage` (deprecated), TWS config (`reqConfig`, `updateConfig`),
  display-group subscribe/update/unsubscribe flows.

## Known implementation gaps

- **BID_ASK double-counting** is documented by IBKR but not modelled; a BID_ASK historical
  request consumes one permit, not two.
- **Keyed historical rules are skipped on the raw generic path**: `send(OutgoingId, MessageLite)`
  passes no `PacingKeys`, so the identical-request and same-contract rules do not apply there.
  The typed `reqHistoricalData` supplies both keys.
- **Typed conveniences cover ~17 requests**; the rest of the 83 outbound ids go through the
  generic `send`.
- **No automatic reconnect loop**; `connect()` retries the handshake only.
- **Pacing has no headroom**: the aggregate is set to the documented `market data lines / 2`.
- **`ContractDetails.minTick` is the smallest tick across all price bands** (0.001 for ASX),
  not the tick for the current price; use `reqMarketRule` for the full schedule.
