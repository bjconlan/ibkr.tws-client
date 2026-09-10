# ibkr-tws

A minimal, Java 25 idiomatic client for the Interactive Brokers TWS API.

This is not a drop-in replacement for IBKR's `com.ib.client` package. It is a
smaller, opinionated rewrite of the parts most programs actually use, built
around three ideas:

- **Callbacks are data.** Server messages are a sealed `IbEvent` hierarchy, so
  handlers use pattern-matching `switch` instead of implementing a 300-method
  `EWrapper`.
- **Blocking is cheap.** The socket reader, writer and event dispatcher each run
  on a virtual thread, so a slow handler cannot stall the wire.
- **Pacing is declared, not improvised.** IBKR's documented request limits are
  values (`PacingRule`) attached to request types, enforced by a `Pacer`.

## Requirements

- JDK 25
- Maven 3.9+
- TWS or IB Gateway reporting server version **201 or later** (the protobuf era).
  The client refuses older servers rather than silently mis-parsing them.

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

Environment overrides: `IBKR_GATEWAY_HOST` (default `127.0.0.1`), `IBKR_GATEWAY_PORT`
(default `4002`), `IBKR_CLIENT_ID` (default `99`). Market data may still be refused with
error `10197` when another session is live.

## What is implemented

| Area | Requests | Events |
|------|----------|--------|
| Session | `startApi`, `reqIds`, `reqCurrentTime` | `Connected`, `ManagedAccounts`, `NextValidId`, `CurrentTime`, `Disconnected`, `Error` |
| Contracts | `reqContractDetails`, `cancelContractDetails` | `ContractDetailsReceived`, `ContractDetailsEnd` |
| Market data | `reqMktData`, `cancelMktData`, `setMarketDataType` | `Tick.Price`, `Tick.Size`, `Tick.Generic`, `Tick.Text`, `Tick.OptionComputation`, `Tick.RequestParams`, `Tick.SnapshotEnd`, `MarketDataType` |
| Historical | `reqHistoricalData`, `cancelHistoricalData` | `HistoricalBar`, `HistoricalDataEnd` |
| Orders | `placeOrder`, `cancelOrder`, `reqOpenOrders` | `OrderStatus`, `OpenOrder`, `OpenOrdersEnd` |
| Account | `reqAccountUpdates`, `reqPositions`, `cancelPositions`, `reqExecutions` | `AccountValueUpdate`, `PortfolioValueUpdate`, `AccountDownloadEnd`, `PositionUpdate`, `PositionsEnd`, `ExecutionDetails`, `ExecutionsEnd`, `CommissionReportReceived` |

Everything else in the upstream API (scanners, news, PnL, market depth,
tick-by-tick, WSH, FA, and so on) is deliberately omitted. Adding a message means
adding one `IncomingId`/`OutgoingId` constant, one proto file, and one branch in
`Decoder`/`Encoder` — the compiler enforces that the decoder switch stays
exhaustive.

## Usage

```java
TwsConfig config = TwsConfig.defaults(clientId);
try (TwsClient client = new TwsClient(config, MarketDataDemo::render)) {
    client.connect();
    client.reqMktData(1, Contract.stock("AAPL"), "", false, false);
    client.reqHistoricalData(2, Contract.stock("AAPL"), "", "1 D", "5 mins", true, "TRADES", 1, false);
    Thread.sleep(30_000);
}
```

A handler is just a function from event to nothing:

```java
private static void render(IbEvent event) {
    switch (event) {
        case IbEvent.Tick.Price p -> render(p.requestId(), p.price());
        case IbEvent.Tick.Size s  -> render(s.requestId(), s.size());
        case IbEvent.OrderStatus o -> track(o.status());
        case IbEvent.Error e -> log.error("[{}] {}: {}", e.requestId(), e.code(), e.message());
        default -> { }
    }
}
```

See `src/main/java/io/github/bjc/ibkr/demo/MarketDataDemo.java`.

## Layout

```
io.github.bjc.ibkr
├── TwsClient          facade; request methods, request-id allocation, lease bookkeeping
├── TwsConfig          connection and retry settings
├── event/IbEvent      sealed hierarchy of everything the server can send
├── model/             records: Contract, Order, Bar, Position, ...
├── protocol/          framing (Wire), request encoding, response decoding, proto mapping
├── pacing/            RequestType, PacingRule(s), Pacer
└── transport/         socket + virtual-thread reader/writer/dispatcher
```

`proto/` holds the upstream `.proto` files re-homed to `io.github.bjc.ibkr.proto`.
They are compiled by `protobuf-maven-plugin`, which downloads the matching
`protoc` binary from Maven Central.

## Protocol notes

- The connect ack is `[ascii serverVersion]\0[time]\0`, length-prefixed. The server version
  is a decimal string, not a raw int (the C++ client parses it with `atoi`, Python with
  `int()`). Raw-int message ids only start after the ack.
- Every post-ack frame is `[length][msgId][protobuf body]`; for protobuf messages the wire id
  is the base id plus 200.

## Pacing

Limits live in `PacingRules` as plain values:

```java
new PacingRule.Rate("ib.historical.60-per-10m", 60, Duration.ofMinutes(10))
new PacingRule.KeyedRate("ib.historical.identical-15s", 1, Duration.ofSeconds(15))
new PacingRule.KeyedRate("ib.historical.same-contract-6-per-2s", 6, Duration.ofSeconds(2))
new PacingRule.Concurrency("ib.marketdata.lines", 100)
```

| Rule | Applies to | Documented limit |
|------|------------|------------------|
| `Rate` | all requests | 50 messages/second (configured at 45) |
| `Rate` | historical data | 60 per 10 minutes |
| `KeyedRate` | historical data | identical request not more than once per 15s |
| `KeyedRate` | historical data | same contract/exchange/tick type max 6 per 2s |
| `Concurrency` | market data | 100 simultaneous lines |
| `Rate` | orders and cancels | 50 per second (configured at 45) |
| `KeyedRate` | executions | conservative 1 per 15s |

Rate rules block the calling thread (cheap on a virtual thread) until a permit is
available, up to `Pacer.DEFAULT_ACQUIRE_TIMEOUT` (30s), after which a
`PacingViolationException` is thrown. Concurrency rules return a `Lease` that is
released on cancel, on snapshot completion, or on disconnect.

`resilience4j` backs the rate limiters (`RateLimiter`), concurrency limits
(`Bulkhead`) and connection retries (`Retry`). It is isolated to `pacing/` and
`TwsClient.connect()`, so a different implementation can be substituted without
touching the protocol code.

## Trade-offs and limitations

- **Protobuf only.** Servers older than version 201 are rejected. Legacy
  text-framed messages are not decoded; the upstream client spends most of its
  size on that path.
- **Narrow surface.** The request/event set above is what is implemented. It is
  chosen to be a working foundation, not API parity.
- **No automatic reconnect loop.** `connect()` retries the initial handshake, but
  a dropped connection emits `Disconnected` and stops. Reconnection policy is left
  to the caller, who may want to re-subscribe explicitly.
- **Field-level lossiness.** The `model` records carry the commonly used fields.
  Rare fields present on the wire are ignored by the mapper rather than surfaced
  as half-typed objects.
- **Pacing values are conservative defaults.** They are the documented ceilings
  minus headroom; adjust `PacingRules.defaults()` to your account's entitlements.

## License

The upstream TWS API sources are GPL-3.0 (Interactive Brokers LLC). The curated
`.proto` files under `proto/` retain that license.
