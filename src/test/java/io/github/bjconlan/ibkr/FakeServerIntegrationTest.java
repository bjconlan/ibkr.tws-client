package io.github.bjconlan.ibkr;

import io.github.bjconlan.ibkr.event.IbEvent;
import io.github.bjconlan.ibkr.model.Contract;
import io.github.bjconlan.ibkr.protocol.IncomingId;
import io.github.bjconlan.ibkr.protocol.OutgoingId;
import io.github.bjconlan.ibkr.protocol.Wire;
import io.github.bjconlan.ibkr.proto.CurrentTimeProto;
import io.github.bjconlan.ibkr.proto.ManagedAccountsProto;
import io.github.bjconlan.ibkr.proto.NextValidIdProto;
import io.github.bjconlan.ibkr.proto.TickPriceProto;
import io.github.bjconlan.ibkr.proto.TickSnapshotEndProto;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a real {@link TwsClient} against a socket-level fake of TWS. This exercises the
 * handshake, framing, request serialisation, virtual-thread reader/writer/dispatcher and the
 * sealed event model without needing a running TWS.
 */
class FakeServerIntegrationTest {

    private static final int SERVER_VERSION = 226;

    @Test
    void handshakeStartApiAndEvents() throws Exception {
        BlockingQueue<IbEvent> events = new LinkedBlockingQueue<>();
        BlockingQueue<Integer> receivedRequests = new LinkedBlockingQueue<>();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> serve(server, receivedRequests));

            TwsConfig config = TwsConfig.defaults(7).withPort(server.getLocalPort());
            try (TwsClient client = new TwsClient(config, events::add)) {
                client.connect();
                assertEquals(SERVER_VERSION, client.serverVersion());
                assertTrue(client.isConnected());

                client.reqCurrentTime();

                awaitAndAssert(events, IbEvent.ManagedAccounts.class, e -> assertEquals(List.of("DU123", "DU456"), e.accounts()));
                awaitAndAssert(events, IbEvent.NextValidId.class, e -> assertEquals(9, e.orderId()));
                awaitAndAssert(events, IbEvent.CurrentTime.class, e -> assertEquals(1_700_000_000L, e.epochSeconds()));

                // The server saw START_API and REQ_CURRENT_TIME, both encoded as protobuf ids.
                List<Integer> requests = List.of(receivedRequests.poll(2, TimeUnit.SECONDS),
                        receivedRequests.poll(2, TimeUnit.SECONDS));
                assertTrue(requests.contains(OutgoingId.START_API.id() + Wire.PROTOBUF_MSG_ID), requests.toString());
                assertTrue(requests.contains(OutgoingId.REQ_CURRENT_TIME.id() + Wire.PROTOBUF_MSG_ID), requests.toString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                serverThread.interrupt();
            }
        }
    }

    @Test
    void snapshotMarketDataProducesTicks() throws Exception {
        BlockingQueue<IbEvent> events = new LinkedBlockingQueue<>();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> serve(server, new LinkedBlockingQueue<>()));

            TwsConfig config = TwsConfig.defaults(8).withPort(server.getLocalPort());
            try (TwsClient client = new TwsClient(config, events::add)) {
                client.connect();
                client.reqMktData(42, Contract.stock("AAPL"), "", true, false);

                awaitAndAssert(events, IbEvent.Tick.Price.class, e -> {
                    assertEquals(42, e.requestId());
                    assertEquals(189.25, e.price());
                });
                awaitAndAssert(events, IbEvent.Tick.SnapshotEnd.class, e -> assertEquals(42, e.requestId()));
            } finally {
                serverThread.interrupt();
            }
        }
    }

    /** Waits for the next event of the expected type, skipping unrelated events. */
    private static <T extends IbEvent> void awaitAndAssert(BlockingQueue<IbEvent> events,
                                                           Class<T> type,
                                                           java.util.function.Consumer<T> assertion) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            IbEvent event = events.poll(5, TimeUnit.SECONDS);
            if (type.isInstance(event)) {
                assertion.accept(type.cast(event));
                return;
            }
        }
        throw new AssertionError("did not receive " + type.getSimpleName());
    }

    private static void serve(ServerSocket server, BlockingQueue<Integer> receivedRequests) {
        try (Socket socket = server.accept()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            byte[] preamble = in.readNBytes(4);
            if (!Arrays.equals(preamble, Wire.API_PREAMBLE)) {
                throw new IllegalStateException("bad preamble: " + Arrays.toString(preamble));
            }
            in.readNBytes(in.readInt()); // version string
            writeHandshake(out, SERVER_VERSION, "20260910-00:00:00");

            while (true) {
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException | java.net.SocketException e) {
                    return;
                }
                byte[] payload = in.readNBytes(length);
                int msgId = Wire.readInt(payload, 0);
                receivedRequests.add(msgId);

                if (msgId == OutgoingId.START_API.id() + Wire.PROTOBUF_MSG_ID) {
                    write(out, IncomingId.MANAGED_ACCTS,
                            ManagedAccountsProto.ManagedAccounts.newBuilder().setAccountsList("DU123,DU456").build());
                    write(out, IncomingId.NEXT_VALID_ID,
                            NextValidIdProto.NextValidId.newBuilder().setOrderId(9).build());
                } else if (msgId == OutgoingId.REQ_CURRENT_TIME.id() + Wire.PROTOBUF_MSG_ID) {
                    write(out, IncomingId.CURRENT_TIME,
                            CurrentTimeProto.CurrentTime.newBuilder().setCurrentTime(1_700_000_000L).build());
                } else if (msgId == OutgoingId.REQ_MKT_DATA.id() + Wire.PROTOBUF_MSG_ID) {
                    write(out, IncomingId.TICK_PRICE, TickPriceProto.TickPrice.newBuilder()
                            .setReqId(42).setTickType(1).setPrice(189.25).setSize("1").setAttrMask(0).build());
                    write(out, IncomingId.TICK_SNAPSHOT_END,
                            TickSnapshotEndProto.TickSnapshotEnd.newBuilder().setReqId(42).build());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Mirrors the real connect ack: {@code [ascii version]\0[time]\0}, length prefixed. */
    private static void writeHandshake(OutputStream out, int version, String time) throws IOException {
        byte[] versionBytes = Integer.toString(version).getBytes(StandardCharsets.US_ASCII);
        byte[] timeBytes = time.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer body = ByteBuffer.allocate(versionBytes.length + 1 + timeBytes.length + 1).order(ByteOrder.BIG_ENDIAN);
        body.put(versionBytes).put((byte) 0).put(timeBytes).put((byte) 0);
        byte[] bodyBytes = body.array();
        ByteBuffer frame = ByteBuffer.allocate(4 + bodyBytes.length).order(ByteOrder.BIG_ENDIAN);
        frame.putInt(bodyBytes.length).put(bodyBytes);
        out.write(frame.array());
        out.flush();
    }

    private static void write(OutputStream out, IncomingId id, com.google.protobuf.MessageLite body) throws IOException {
        out.write(Wire.frame(id, body));
        out.flush();
    }
}
