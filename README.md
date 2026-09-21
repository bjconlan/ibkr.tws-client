# ibkr.tws-client

A minimal, Java 25 idiomatic client for the Interactive Brokers TWS API.

This is not a drop-in replacement for IBKR's `com.ib.client` package. It is a
smaller, opinionated rewrite of the parts most programs actually use, built
around three ideas:

- **Callbacks are data.** Server messages are a sealed `IbEvent` hierarchy carrying the
  generated protobuf payloads, so handlers use pattern-matching `switch` instead of
  implementing a 300-method `EWrapper`, and no field is remapped into a parallel type.
- **Blocking is cheap.** The socket reader, writer and event dispatcher each run
  on a virtual thread, so a slow handler cannot stall the wire.
- **Pacing is declared, not improvised.** IBKR's documented request limits are
  values (`PacingRule`) attached to outbound request ids and enforced by a `Pacer`;
  the aggregate limit is derived from the account's market data lines.

## Requirements

- JDK 25
- Maven 3.9+
- TWS or IB Gateway reporting server version **201 or later** (the protobuf era). The
  handshake advertises `v100..226`; older servers are refused rather than silently
  mis-parsed. See [Compatibility](#compatibility) for the exact versions.

The toolchain is pinned with `mise`:

```sh
mise install
mvn test
```

### Tests

| Command | What runs |
|---------|-----------|
| `mvn test` | Unit tests: codec, pacer, fake-TWS socket test. No Docker, no network. |
| `mvn verify` | The unit tests plus `IbGatewayContainerIT`, if credentials and Docker are available. |

Opt-in probes for a running gateway (`ContractProbeTest`, `GatewaySmokeTest`, `PaperOrderTest`,
`AccountStreamsTest`, `FeatureProbeTest`) and a record of what has and has not been exercised
against a real account are in [`docs/verification-status.md`](docs/verification-status.md).

`IbGatewayContainerIT` starts a paper IB Gateway with Testcontainers (the same
`ghcr.io/gnzsnz/ib-gateway` image as `~/Workspace/lo.fi/compose.yaml`), waits for IBC's
"Login has completed" log line plus the API port, then connects, resolves `AAPL` contract
details and requests current time.

Credentials come from a git-ignored `.env` at the project root, or from the
`TWS_USERID` / `TWS_PASSWORD` environment variables (which take precedence):

```sh
cp .env.example .env   # then fill in paper credentials
mvn verify
```

Without credentials or Docker the integration test is skipped, not failed. Run it directly
with `mvn verify -Dit.test=IbGatewayContainerIT`. The container is read-only, so no orders
are placed.

There is also `GatewaySmokeTest`, which points at an *already running* gateway instead of
starting one:

```sh
IBKR_SMOKE=true IBKR_GATEWAY_PORT=4002 mvn test -Dtest=GatewaySmokeTest
```

`PaperOrderTest` is an opt-in order lifecycle probe against a **non-read-only** paper gateway
(a container started with `READ_ONLY_API=no`). It resolves FMG on ASX and covers two paths: a
resting limit buy that is checked then cancelled, and a marketable limit buy that is allowed to
fill and is then flattened with a market sell. The fill path also exercises the execution and
commission callbacks:

```sh
IBKR_ORDER=true IBKR_GATEWAY_PORT=4002 mvn test -Dtest=PaperOrderTest
IBKR_ORDER=true IBKR_GATEWAY_PORT=4002 mvn test -Dtest='PaperOrderTest#fillAndFlattenOnOpenMarket'
```

Note that `ContractDetails.minTick` is the smallest tick across all price bands (0.001 for ASX
stocks), not the tick for the current price; FMG trades in 0.01 increments, so the probe picks
that explicitly. Use `reqMarketRule` if you need the full schedule.

Environment overrides: `IBKR_GATEWAY_HOST` (default `127.0.0.1`), `IBKR_GATEWAY_PORT`
(default `4002`), `IBKR_CLIENT_ID` (default `99`). Market data may still be refused with
error `10197` when another session is live.

## Verification

Exercised against a paper IB Gateway (server 226, account `DU5415404`, no market data
subscription, so delayed data) on 2026-09-14. Full detail, including how to run each probe, is in
[`docs/verification-status.md`](docs/verification-status.md).

### Tested

- **Session** — `API\0` handshake, server-version negotiation, connect / disconnect / error.
  Across every probe no `unknown message id` (505) was observed, so the `IncomingId` wiring covers
  everything the server sent.
- **Reference data** — matching symbols, market rule, depth exchanges, family codes, soft dollar
  tiers, sec-def opt params (39 sets), news providers, symbol samples, user info, current time in
  millis, display groups.
- **Account** — account summary (with tags), account updates, account updates multi, positions
  multi, PnL.
- **Historical / news** — contract details, historical bars, head timestamp, histogram, historical
  ticks (TRADES, BID_ASK, MIDPOINT), historical news and a news article fetched by id.
- **Scanner** — parameters and a live scanner subscription.
- **Orders (read-write)** — resting limit buy submitted, echoed by `reqOpenOrders`, cancelled;
  marketable limit buy `PreSubmitted` → `Filled`, then a market sell to flatten, with
  `ExecutionDetails` and `CommissionAndFeesReport` for both legs and the position back to zero.

### Not tested

- **Market data that needs a subscription** — market depth (L1/L2), tick-by-tick, real-time bars,
  smart components, tick news, the live/frozen/regulatory market data types, and generic tick
  lists. These requests are refused with `10189`/`420`/`2152` on an account without a feed.
- **WSH** — `reqWshMetaData` / `reqWshEventData` returned nothing on this account.
- **Order types beyond LMT/MKT** — stop, trailing, bracket/OCA/attached, algo, order conditions,
  fractional, short sales, combos; plus `whatIf` preview, modify/replace, global cancel,
  auto-open orders, order bound, partial fills, rejections, GTD/GTC expiry and extended hours.
- **Instruments** — FX, futures, options, bonds and combos are untested; crypto contract data and
  market data resolve, but the paper account rejects crypto orders (`201 Invalid account`).
- **Portfolio / FA** — a non-flat portfolio, PnL alongside a position, `reqPnLSingle` for a held
  contract, model/FA flows, and the full account-summary tag set.
- **Historical matrix** — bar sizes and durations, `whatToShow` values other than
  TRADES/BID_ASK/MIDPOINT, `formatDate=2`, `keepUpToDate`, `SCHEDULE`, and FX/futures history.
- **Resilience and concurrency** — reconnect after a dropped socket, multiple clients, version
  boundaries (201–225 and above 226), TLS, malformed frames, parallel virtual-thread load, and
  actually breaching a pacing limit.
- **Legacy / admin** — `verifyRequest`/`verifyMessage`, `reqConfig`/`updateConfig`, and the
  display-group subscribe/update/unsubscribe flows.

Implementation gaps (as opposed to test coverage) are listed under
[Trade-offs and limitations](#trade-offs-and-limitations) — notably BID_ASK double-counting and
keyed historical rules on the generic `send` path.

## Compatibility

| Component | Version | Notes |
|-----------|---------|-------|
| Upstream TWS API | **10.50.02** (`twsapi_macunix.1050.02.zip`) | from `interactivebrokers.github.io/downloads`; `API_VersionNum.txt` and the Python package report 10.50.02 while the upstream Java `pom.xml` says 10.50.01 |
| Envelope version | `v100..226` | advertised in the `API\0` handshake |
| Server version | **201..226** | 201 is `MIN_SERVER_VER_PROTOBUF`; anything below is legacy text and is refused |
| Protobuf runtime | `com.google.protobuf:protobuf-java:4.29.5` | matches the upstream `protobuf-java-4.29.5.jar` |
| `protoc` | 4.29.5 | downloaded by `protobuf-maven-plugin` from Maven Central |
| JDK | 25 | `maven.compiler.release=25` |
| Validated against | IB Gateway 10.50.1e (`ghcr.io/gnzsnz/ib-gateway`), server 226 | paper account, read-only API |
| resilience4j | 2.4.0 | `ratelimiter`, `bulkhead`, `retry`, `circuitbreaker` |
| JUnit / Testcontainers | 6.1.3 / 1.21.4 | test scope only |

Notes:

- The client advertises a maximum of server version **226**. A newer gateway that reports a higher
  version would still speak the same ids for the implemented messages, but the negotiation cap
  should be raised (`Wire.MAX_VERSION`) and the client re-tested.
- **All 200** upstream proto files are vendored under `proto/`, re-homed to
  `io.github.bjconlan.ibkr.proto`; field numbers and types are untouched. Every inbound
  message with a protobuf variant is decodable and every outbound id is sendable.
  Re-vendor from the same 10.50.02 release when upgrading.
- Protobuf is required. TWS/Gateway releases old enough to report server version `< 201` use the
  legacy text framing and are not supported.

## Message coverage

All 80 inbound messages that have a protobuf variant are decoded, and all 83 outbound ids can
be sent, so the wire surface is at parity with the upstream API:

- **Receiving** — every server message arrives as `IbEvent.Message` carrying its generated
  protobuf class. The decoder switch over `IncomingId` is exhaustive, so a new id cannot be
  added without a parser.
- **Sending** — `session.submit(TwsRequest)` returns a future, `session.stream(TwsRequest)` a JDK
  `Stream`, and `session.on(TwsRequest, handler)` a push subscription. `connection.send(OutgoingId,
  message)` frames and paces any request. The typed methods on `TwsConnection` (`reqMktData`,
  `reqHistoricalData`, `placeOrder`, ...) are conveniences over the same path; the request-id-taking
  ones are deprecated in favour of `TwsSession`.

The five upstream ids with no protobuf variant (`END_CONN`, `TICK_EFP`,
`DELTA_NEUTRAL_VALIDATION`, `VERIFY_AND_AUTH_MESSAGE_API`, `VERIFY_AND_AUTH_COMPLETED`) exist
only in the legacy text framing and are not part of a protobuf-only client. Connection end
arrives as `IbEvent.Disconnected`, and the delta-neutral contract is a field of `Contract`.

## Sample

The bundled demo connects, streams `AAPL` market data and fetches one day of 5-minute bars:

```sh
mvn -q test-compile exec:java                                   # 127.0.0.1:7497, clientId 1
mvn -q test-compile exec:java -Dexec.args="127.0.0.1 4002 11"   # host, port, clientId
```

The demo deliberately lives under `src/test/java`, not in the published jar:
`src/test/java/io/github/bjconlan/ibkr/demo/MarketDataDemo.java`. The runnable core:

```java
public final class MarketDataDemo {

    public static void main(String[] args) throws Exception {
        TwsConfig config = TwsConfig.defaults(1).withPort(4002)
                .withMarketDataType(MarketDataType.DELAYED);   // paper gateway, no live entitlement
        List<Integer> clientIds = List.of(1, 2);                   // the client ids this service owns
        ContractProto.Contract aapl = ContractProto.Contract.newBuilder()
                .setSymbol("AAPL").setSecType("STK").setExchange("SMART").setCurrency("USD").build();

        try (TwsClientFactory factory = new TwsClientFactory(config, MarketDataDemo::render, clientIds);
             TwsSession session = factory.createSession()) {
            // Push: wait for ten ticks, not for a fixed time.
            CountDownLatch ticks = new CountDownLatch(10);
            try (AutoCloseable subscription = session.on(
                    TwsRequest.marketData(aapl, "", false, false),
                    m -> { renderMessage(m); ticks.countDown(); })) {
                if (!ticks.await(30, TimeUnit.SECONDS)) {
                    System.err.println("timed out waiting for ticks");
                }
            }   // closing cancels the subscription and releases the market data line

            // Bounded: blocks until the terminal message, then prints every bar.
            session.submit(TwsRequest.historicalData(aapl, "", "1 D", "5 mins", "TRADES", true, 1, false))
                    .join()
                    .forEach(MarketDataDemo::renderMessage);
        }
    }

    private static void render(IbEvent event) {
        switch (event) {
            case IbEvent.Error e -> System.err.printf("[%d] %d: %s%n", e.requestId(), e.code(), e.message());
            case IbEvent.Disconnected d -> System.out.println("disconnected: " + d.reason());
            default -> { }
        }
    }

    private static void renderMessage(IbEvent.Message m) {
        switch (m.payload()) {
            case TickPriceProto.TickPrice p -> System.out.printf("price %.4f%n", p.getPrice());
            case HistoricalDataProto.HistoricalData h ->
                    h.getHistoricalDataBarsList().forEach(b -> System.out.printf("%s %.2f%n", b.getDate(), b.getClose()));
            default -> { }
        }
    }
}
```

### Sessions

Request handling is scoped through a `TwsSession`, created with `connection.createSession()` for
a single connection or `factory.createSession()` for a pool. The
connection allocates request ids, applies pacing and routes responses; the session chooses how
they are consumed and cancels everything it opened when closed:

- `submit(TwsRequest)` - bounded; a `CompletableFuture` of the correlated responses.
- `stream(TwsRequest)` - pull; a JDK `Stream` that ends on the terminal message, and cancels when closed.
- `on(TwsRequest, handler)` - push; the handler runs on the dispatcher until the handle is closed.

No `Thread.sleep` is needed. Bounded work blocks on `submit(...).join()`; streaming waits on a
latch or on the next `stream` element, or just holds the subscription for the scope's lifetime. In
Spring the `TwsSession` (or the `TwsConnection`) is a bean whose `close()`/`@PreDestroy` cancels
the requests.

### Pooling and Spring

`TwsClientFactory` owns a pool of connections, one per client id you reserve for the service, and
hands out `TwsSession`s. Each connection gets its own *rate* budget (IBKR's aggregate request rate
is per connection), while the pool shares one `SubscriptionBudget` so the market data line and
tick-by-tick caps stay account-wide. Sessions do not lease a connection: each request is placed on
a live connection round-robin, and closing a session cancels only its own requests. Connections
are dialled lazily - nothing opens until a request needs it, and each reserved client id is
dialled the first time the round-robin selects it. The two-argument constructor defaults the
reserved ids to `IntStream.range(1, 32)` (1-31); pass an explicit list to avoid ids used by other
API clients.

```java
TwsClientFactory factory = new TwsClientFactory(
        TwsConfig.defaults(0).withPort(4002)
                .withMarketDataType(MarketDataType.DELAYED),   // connection setting; omit to use TWS's own
        event -> log.info("ibkr: {}", event),
        List.of(1, 2, 3, 4));      // the client ids this service owns; omit any used elsewhere

try (TwsSession session = factory.createSession()) {
    ...
}   // cancels this session's requests

factory.close();                  // closes every dialled connection
```

In Spring the factory is a bean whose lifecycle spans the application, and subscriptions live in
long-lived components. No `Thread.sleep`, no request ids, no connection bookkeeping. Bind the
connection settings from configuration, expose the factory as a singleton whose `close()` runs on
shutdown, and let each long-lived component own a session:

```java
@ConfigurationProperties("ibkr")
record IbkrProperties(String host, int port, List<Integer> clientIds, MarketDataType marketDataType) {

    TwsConfig config() {
        TwsConfig base = TwsConfig.defaults(0)        // the client id is set per pooled connection
                .withHost(host)
                .withPort(port);
        return marketDataType == null ? base : base.withMarketDataType(marketDataType);
    }
}

@Configuration
@EnableConfigurationProperties(IbkrProperties.class)
class IbkrConfiguration {

    /** One factory for the application; destroyMethod closes every dialled connection on shutdown. */
    @Bean(destroyMethod = "close")
    TwsClientFactory twsClientFactory(IbkrProperties properties) {
        return new TwsClientFactory(
                properties.config(),
                event -> log.info("ibkr: {}", event),   // connection-level lifecycle and errors
                properties.clientIds());                // the client ids this service owns
    }
}

@Component
class MarketDataFeed implements AutoCloseable {

    private static final ContractProto.Contract AAPL = ContractProto.Contract.newBuilder()
            .setSymbol("AAPL").setSecType("STK").setExchange("SMART").setCurrency("USD").build();

    private final TwsSession session;
    private final AutoCloseable subscription;

    MarketDataFeed(TwsClientFactory factory) {
        this.session = factory.createSession();                       // one scope for the bean's lifetime
        this.subscription = session.on(TwsRequest.marketData(AAPL, ""), this::persist);
    }

    private void persist(IbEvent.Message message) {
        // runs on the connection's dispatcher thread; keep it short or hand off to a queue
    }

    @PreDestroy
    @Override
    public void close() throws Exception {
        subscription.close();
        session.close();
    }
}
```

```yaml
ibkr:
  host: 127.0.0.1
  port: 4002
  client-ids: [1, 2, 3, 4]
  market-data-type: DELAYED    # omit to leave the TWS/Gateway-configured type in force
```

### Using it as a library

It is not published to a registry; install it into the local Maven repository:

```sh
mvn install
```

```xml
<dependency>
    <groupId>io.github.bjconlan.ibkr</groupId>
    <artifactId>tws-client</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Request methods are synchronous and may block on the pacer, so issue independent requests from
virtual threads, each with its own future:

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var a = executor.submit(() -> session.submit(
            TwsRequest.historicalData(aapl, "", "1 D", "5 mins", "TRADES", true, 1, false)).join());
    var b = executor.submit(() -> session.submit(
            TwsRequest.historicalData(aapl, "", "1 W", "1 hour", "TRADES", true, 1, false)).join());
    var both = CompletableFuture.allOf(a, b).thenRun(() -> { });
    both.join();
}
```

## Layout

```
io.github.bjconlan.ibkr
├── TwsClientFactory   pool of connections over a reserved client-id list
├── TwsConnection      one client id + socket; connect/disconnect, request-id allocation, routing
├── TwsSession         request scope; submit / stream / on
├── TwsRequest         request descriptors (contractDetails, historicalData, marketData, executions)
├── TwsConfig          connection and retry settings
├── event/IbEvent      sealed hierarchy: lifecycle records + Message(id, protobuf payload)
├── protocol/          framing (Wire), request encoding, message decoding, id maps
├── pacing/            PacingRule(s), PacingKeys, Pacer, SubscriptionBudget
└── transport/         socket + virtual-thread reader/writer/dispatcher
```

`proto/` holds all 200 upstream `.proto` files (from TWS API 10.50.02) re-homed to
`io.github.bjconlan.ibkr.proto`. They are compiled by `protobuf-maven-plugin`, which downloads the
matching `protoc` 4.29.5 binary from Maven Central.

## Protocol notes

- The connect ack is `[ascii serverVersion]\0[time]\0`, length-prefixed. The server version
  is a decimal string, not a raw int (the C++ client parses it with `atoi`, Python with
  `int()`). Raw-int message ids only start after the ack.
- Every post-ack frame is `[length][msgId][protobuf body]`; for protobuf messages the wire id
  is the base id plus 200.

## Pacing

IBKR documents pacing in two layers: one aggregate request limit per connection, and a few
per-request constraints. This client models only what is documented; rules live in
`PacingRules` as plain values and are enforced by `Pacer`.

### Aggregate request limit

From [Pacing Limitations → Introduction](https://www.interactivebrokers.com/docs/tws-api/doc/pacing-limitations/introduction):

> The maximum number of API requests that can be submitted are equivalent to your Maximum
> Market Data Lines **divided by 2, per second**. By default, all users maintain 100 market
> data lines. Therefore, users have a pacing limitation of (100/2) = **50 requests per second**.
> … Clients that have increased their market data lines to 200 … would receive (200/2) = 100
> requests per second, and this would increment as your market data lines increase or decrease.

It is a **per-connection aggregate over every request type**, and only the request that starts
a subscription counts — streaming responses do not. The client derives this limit from
`TwsConfig.marketDataLines()` (default `100`):

```java
TwsConfig config = TwsConfig.defaults(1).withMarketDataLines(200);  // 100 requests/second
```

### Documented per-request constraints

| Rule | Applies to | Documented limit |
|------|-----------|------------------|
| `Rate` (`ib.pacing.requests`) | every request | market data lines ÷ 2 per second (aggregate) |
| `Rate` (`ib.historical.60-per-10m`) | `REQ_HISTORICAL_DATA` | more than 60 requests in any ten minute period |
| `KeyedRate` (`ib.historical.identical-15s`, scope `REQUEST`) | `REQ_HISTORICAL_DATA` | identical requests within 15 seconds |
| `KeyedRate` (`ib.historical.same-contract-6-per-2s`, scope `CONTRACT_EXCHANGE_TICK_TYPE`) | `REQ_HISTORICAL_DATA` | six or more requests for the same contract, exchange and tick type within two seconds |
| `Concurrency` (`ib.marketdata.lines`) | `REQ_MKT_DATA` | market data lines outstanding at once |
| `Concurrency` (`ib.tickbytick.subscriptions`) | `REQ_TICK_BY_TICK_DATA` | 5% of market data lines outstanding at once |

The historical limits are quoted from [Pacing Violations for Small Bars](https://www.interactivebrokers.com/docs/tws-api/doc/market-data-historical/historical-data-limitations/pacing-violations-for-small-bars-30-secs-or-less).
The concurrent market data line count comes from [How Market Data is Allocated](https://www.interactivebrokers.com/docs/general/market-data-subscriptions/market-data-lines/how-market-data-is-allocated)
(100 minimum, scaling with commissions and equity), and the tick-by-tick cap from
[Request Tick By Tick Data](https://www.interactivebrokers.com/docs/tws-api/doc/market-data-live/tick-by-tick-data/request-tick-by-tick-data).

There is deliberately **no** order, cancel or execution rate rule: the current documentation
defines none, so those requests are governed only by the aggregate limit.

### Behaviour

`Rate` and `KeyedRate` block the calling thread (cheap on a virtual thread) until a permit is
available, up to `Pacer.DEFAULT_ACQUIRE_TIMEOUT` (30s), after which a
`PacingViolationException` is thrown instead of sending a request IBKR would reject.
`Concurrency` rules return a `Lease` that is released on cancel, on snapshot completion, or on
disconnect. This is the client-side counterpart to IBKR's gateway behaviour, where breaking the
limit raises [error **100** and terminates the session after **3** violations](https://www.interactivebrokers.com/docs/tws-api/doc/pacing-limitations/pacing-behavior),
or the gateway silently paces if configured that way.

### Custom rules

Pass a `Pacer` to override the defaults for any outbound id:

```java
Pacer pacer = new Pacer(
        PacingRules.global(200),                       // aggregate: 100/s
        Map.of(OutgoingId.REQ_MKT_DATA,
                List.of(new PacingRule.Concurrency("ib.marketdata.lines", 50))),
        Duration.ofSeconds(30));

TwsConnection connection = TwsConnection.open(config, handler, pacer);
```

Rules are keyed by `OutgoingId`, so any request can carry its own policy. `PacingRule` is a
sealed interface with three shapes: `Rate`, `KeyedRate` (with a `REQUEST` or
`CONTRACT_EXCHANGE_TICK_TYPE` scope) and `Concurrency`.

`resilience4j` backs the rate limiters (`RateLimiter`), concurrency limits (`Bulkhead`) and
connection retries (`Retry`). It is isolated to `pacing/` and `TwsConnection.open()`, so a
different implementation can be substituted without touching the protocol code.

One documented rule is not modelled: **BID_ASK historical requests count twice**. If you use
`whatToShow = "BID_ASK"`, budget for half the headline historical rate yourself.

## Trade-offs and limitations

- **Protobuf only.** Servers older than version 201 are rejected. Legacy
  text-framed messages are not decoded; the upstream client spends most of its
  size on that path.
- **No per-message records.** Payloads are the generated protobuf classes. Handlers get
  `getX()`/`hasX()` accessors rather than record deconstruction, and a pattern `switch` over
  payload types needs a `default` because generated classes are not sealed. Dispatch on
  `IbEvent.Message.id()` (an enum switch) if you want exhaustiveness.
- **No automatic reconnect loop.** `connect()` retries the initial handshake, but
  a dropped connection emits `Disconnected` and stops. Reconnection policy is left
  to the caller, who may want to re-subscribe explicitly.
- **Pacing follows the documented limits.** The aggregate rate is market data lines ÷ 2
  (`TwsConfig.marketDataLines`), so set it to your entitlement; the defaults assume the
  minimum of 100 lines. BID_ASK double-counting is documented but not modelled, and the keyed
  historical rules (identical request, same contract) apply on the typed `reqHistoricalData` —
  the raw `send(OutgoingId, MessageLite)` path supplies no keys and so skips them.
- **Partial typed surface.** `TwsConnection` has typed conveniences for the common requests (~17 of
  the 83 outbound ids); everything else goes through `send(OutgoingId, MessageLite)` with the
  generated protobuf message.

## License

The upstream TWS API sources are GPL-3.0 (Interactive Brokers LLC). The curated
`.proto` files under `proto/` retain that license.
