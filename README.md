# ibkr-tws

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
  values (`PacingRule`) attached to request types, enforced by a `Pacer`.

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
- **Sending** — `client.send(OutgoingId, message)` frames and paces any request. The typed
  methods on `TwsClient` (`reqMktData`, `reqHistoricalData`, `placeOrder`, ...) are
  conveniences over the same path.

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
        TwsConfig config = TwsConfig.defaults(1).withPort(4002);   // paper gateway
        ContractProto.Contract aapl = ContractProto.Contract.newBuilder()
                .setSymbol("AAPL").setSecType("STK").setExchange("SMART").setCurrency("USD").build();

        try (TwsClient client = new TwsClient(config, MarketDataDemo::render)) {
            client.connect();
            client.reqMktData(1, aapl, "", false, false);
            client.reqHistoricalData(2, aapl, "", "1 D", "5 mins", true, "TRADES", 1, false);

            Thread.sleep(30_000);   // events arrive on the dispatcher thread
            client.cancelMktData(1);
        }
    }

    private static void render(IbEvent event) {
        switch (event) {
            case IbEvent.Message m when m.payload() instanceof TickPriceProto.TickPrice p ->
                    System.out.printf("price %.4f%n", p.getPrice());
            case IbEvent.Message m when m.payload() instanceof HistoricalDataProto.HistoricalData h ->
                    h.getHistoricalDataBarsList().forEach(b -> System.out.printf("%s %.2f%n", b.getDate(), b.getClose()));
            case IbEvent.Error e -> System.err.printf("[%d] %d: %s%n", e.requestId(), e.code(), e.message());
            case IbEvent.Disconnected d -> System.out.println("disconnected: " + d.reason());
            default -> { }
        }
    }
}
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
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Request methods are synchronous and may block on the pacer, so issue independent requests from
virtual threads:

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    executor.submit(() -> client.reqHistoricalData(1, aapl, "", "1 D", "5 mins", true, "TRADES", 1, false));
    executor.submit(() -> client.reqHistoricalData(2, aapl, "", "1 W", "1 hour", true, "TRADES", 1, false));
}
```

## Layout

```
io.github.bjconlan.ibkr
├── TwsClient          facade; request methods, request-id allocation, lease bookkeeping
├── TwsConfig          connection and retry settings
├── event/IbEvent      sealed hierarchy: lifecycle records + Message(id, protobuf payload)
├── protocol/          framing (Wire), request encoding, message decoding, id maps
├── pacing/            RequestType, PacingRule(s), Pacer
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
- **No per-message records.** Payloads are the generated protobuf classes. Handlers get
  `getX()`/`hasX()` accessors rather than record deconstruction, and a pattern `switch` over
  payload types needs a `default` because generated classes are not sealed. Dispatch on
  `IbEvent.Message.id()` (an enum switch) if you want exhaustiveness.
- **No automatic reconnect loop.** `connect()` retries the initial handshake, but
  a dropped connection emits `Disconnected` and stops. Reconnection policy is left
  to the caller, who may want to re-subscribe explicitly.
- **Pacing values are conservative defaults.** They are the documented ceilings
  minus headroom; adjust `PacingRules.defaults()` to your account's entitlements.

## License

The upstream TWS API sources are GPL-3.0 (Interactive Brokers LLC). The curated
`.proto` files under `proto/` retain that license.
