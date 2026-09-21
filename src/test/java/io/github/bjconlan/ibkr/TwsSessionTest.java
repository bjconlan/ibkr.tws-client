package io.github.bjconlan.ibkr;

import io.github.bjconlan.ibkr.event.IbEvent;
import io.github.bjconlan.ibkr.protocol.IncomingId;
import io.github.bjconlan.ibkr.protocol.OutgoingId;
import io.github.bjconlan.ibkr.protocol.Wire;
import io.github.bjconlan.ibkr.proto.CancelMarketDataProto;
import io.github.bjconlan.ibkr.proto.ContractDataEndProto;
import io.github.bjconlan.ibkr.proto.ContractDataRequestProto;
import io.github.bjconlan.ibkr.proto.ContractDataProto;
import io.github.bjconlan.ibkr.proto.ContractProto;
import io.github.bjconlan.ibkr.proto.MarketDataRequestProto;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@link TwsSession} façades against a socket-level fake of TWS: the connection
 * allocates the reqId, the server echoes it, and {@code submit}/{@code stream}/{@code on} receive
 * only the responses correlated to that request.
 */
class TwsSessionTest {

    private static final int SERVER_VERSION = 226;

    @Test
    void submitCompletesWithCorrelatedResponses() throws Exception {
        BlockingQueue<Integer> cancelled = new LinkedBlockingQueue<>();
        BlockingQueue<Integer> marketData = new LinkedBlockingQueue<>();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> serve(server, cancelled, marketData));
            try (TwsConnection connection = connect(server)) {
                TwsSession session = connection.createSession();
                List<IbEvent.Message> messages =
                        session.submit(TwsRequest.contractDetails(contract("AAPL"))).get(5, TimeUnit.SECONDS);

                assertEquals(2, messages.size(), messages.toString());
                assertTrue(messages.get(0).payload() instanceof ContractDataProto.ContractData, messages.toString());
                assertTrue(messages.get(1).payload() instanceof ContractDataEndProto.ContractDataEnd, messages.toString());
            }
            serverThread.interrupt();
        }
        assertTrue(cancelled.isEmpty(), "bounded request must not send a cancel");
    }

    @Test
    void streamEndsOnTerminalAndClosesScope() throws Exception {
        BlockingQueue<Integer> cancelled = new LinkedBlockingQueue<>();
        BlockingQueue<Integer> marketData = new LinkedBlockingQueue<>();
        List<IbEvent.Message> received = new ArrayList<>();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> serve(server, cancelled, marketData));
            try (TwsConnection connection = connect(server)) {
                TwsSession session = connection.createSession();
                try (Stream<IbEvent.Message> stream = session.stream(TwsRequest.contractDetails(contract("AAPL")))) {
                    stream.forEach(received::add);
                }
            }
            serverThread.interrupt();
        }

        assertEquals(2, received.size(), received.toString());
    }

    @Test
    void onPushesResponsesUntilClosed() throws Exception {
        BlockingQueue<Integer> cancelled = new LinkedBlockingQueue<>();
        BlockingQueue<Integer> marketData = new LinkedBlockingQueue<>();
        List<IbEvent.Message> received = new ArrayList<>();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> serve(server, cancelled, marketData));
            try (TwsConnection connection = connect(server)) {
                TwsSession session = connection.createSession();
                AutoCloseable handle = session.on(
                        TwsRequest.marketData(contract("AAPL"), "", false, false), received::add);

                awaitNonEmpty(received);
                handle.close();
            }
            serverThread.interrupt();
        }

        assertTrue(!received.isEmpty(), "expected at least one pushed tick");
    }

    @Test
    void sessionCloseCancelsOpenRequests() throws Exception {
        BlockingQueue<Integer> cancelled = new LinkedBlockingQueue<>();
        BlockingQueue<Integer> marketData = new LinkedBlockingQueue<>();
        List<IbEvent.Message> received = new ArrayList<>();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(() -> serve(server, cancelled, marketData));
            try (TwsConnection connection = connect(server)) {
                TwsSession session = connection.createSession();
                session.on(TwsRequest.marketData(contract("AAPL"), "", false, false), received::add);

                Integer reqId = marketData.poll(5, TimeUnit.SECONDS);
                assertNotNull(reqId, "server never saw the market data request");

                session.close();

                Integer seen = cancelled.poll(5, TimeUnit.SECONDS);
                assertEquals(reqId, seen, "session close must cancel the open request");
            }
            serverThread.interrupt();
        }
    }

    @Test
    void submitRejectsOpenEndedRequest() {
        TwsRequest openEnded = TwsRequest.marketData(contract("AAPL"), "", false, false);
        TwsSession session = new TwsSession(() -> {
            throw new AssertionError("no connection expected");
        });
        assertThrows(IllegalArgumentException.class, () -> session.submit(openEnded));
    }

    // ------------------------------------------------------------------ helpers

    private static TwsConnection connect(ServerSocket server) throws Exception {
        return TwsConnection.open(TwsConfig.defaults(7).withPort(server.getLocalPort()), ignored -> { });
    }

    private static void awaitNonEmpty(List<IbEvent.Message> received) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (received.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private static ContractProto.Contract contract(String symbol) {
        return ContractProto.Contract.newBuilder()
                .setSymbol(symbol).setSecType("STK").setExchange("SMART").setCurrency("USD")
                .build();
    }

    private static void serve(ServerSocket server, BlockingQueue<Integer> cancelled,
                              BlockingQueue<Integer> marketData) {
        try (Socket socket = server.accept()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            byte[] preamble = in.readNBytes(4);
            if (!Arrays.equals(preamble, Wire.API_PREAMBLE)) {
                throw new IllegalStateException("bad preamble: " + Arrays.toString(preamble));
            }
            in.readNBytes(in.readInt()); // version string
            writeHandshake(out, SERVER_VERSION);

            while (true) {
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException | java.net.SocketException e) {
                    return;
                }
                byte[] payload = in.readNBytes(length);
                int msgId = Wire.readInt(payload, 0);
                byte[] body = Arrays.copyOfRange(payload, 4, payload.length);

                if (msgId == OutgoingId.REQ_CONTRACT_DATA.id() + Wire.PROTOBUF_MSG_ID) {
                    int reqId = ContractDataRequestProto.ContractDataRequest.parseFrom(body).getReqId();
                    write(out, IncomingId.CONTRACT_DATA,
                            ContractDataProto.ContractData.newBuilder().setReqId(reqId).build());
                    write(out, IncomingId.CONTRACT_DATA_END,
                            ContractDataEndProto.ContractDataEnd.newBuilder().setReqId(reqId).build());
                } else if (msgId == OutgoingId.REQ_MKT_DATA.id() + Wire.PROTOBUF_MSG_ID) {
                    MarketDataRequestProto.MarketDataRequest request =
                            MarketDataRequestProto.MarketDataRequest.parseFrom(body);
                    int reqId = request.getReqId();
                    marketData.add(reqId);
                    write(out, IncomingId.TICK_PRICE,
                            TickPriceProto.TickPrice.newBuilder()
                                    .setReqId(reqId).setTickType(1).setPrice(189.25).setSize("1").setAttrMask(0)
                                    .build());
                    if (request.getSnapshot()) {
                        write(out, IncomingId.TICK_SNAPSHOT_END,
                                TickSnapshotEndProto.TickSnapshotEnd.newBuilder().setReqId(reqId).build());
                    }
                } else if (msgId == OutgoingId.CANCEL_MKT_DATA.id() + Wire.PROTOBUF_MSG_ID) {
                    cancelled.add(CancelMarketDataProto.CancelMarketData.parseFrom(body).getReqId());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeHandshake(OutputStream out, int version) throws IOException {
        byte[] versionBytes = Integer.toString(version).getBytes(StandardCharsets.US_ASCII);
        byte[] body = new byte[versionBytes.length + 2];
        System.arraycopy(versionBytes, 0, body, 0, versionBytes.length);
        ByteBuffer frame = ByteBuffer.allocate(4 + body.length).order(ByteOrder.BIG_ENDIAN);
        frame.putInt(body.length).put(body);
        out.write(frame.array());
        out.flush();
    }

    private static void write(OutputStream out, IncomingId id, com.google.protobuf.MessageLite body)
            throws IOException {
        out.write(Wire.frame(id, body));
        out.flush();
    }
}
