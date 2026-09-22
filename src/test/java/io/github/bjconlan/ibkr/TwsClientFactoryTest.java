package io.github.bjconlan.ibkr;

import io.github.bjconlan.ibkr.protocol.IncomingId;
import io.github.bjconlan.ibkr.protocol.OutgoingId;
import io.github.bjconlan.ibkr.protocol.Wire;
import io.github.bjconlan.ibkr.proto.ContractDataEndProto;
import io.github.bjconlan.ibkr.proto.ContractDataRequestProto;
import io.github.bjconlan.ibkr.proto.ContractDataProto;
import io.github.bjconlan.ibkr.proto.ContractProto;
import io.github.bjconlan.ibkr.proto.StartApiRequestProto;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link TwsClientFactory} against a fake TWS that accepts connections on demand: the
 * pool dials each client id lazily, distributes requests across them, and closes them all.
 */
class TwsClientFactoryTest {

    private static final int SERVER_VERSION = 226;

    @Test
    void dialsLazilyAndSpreadsRequests() throws Exception {
        List<Integer> connectedIds = new CopyOnWriteArrayList<>();
        BlockingQueue<Integer> servedBy = new LinkedBlockingQueue<>();
        BlockingQueue<Integer> disconnected = new LinkedBlockingQueue<>();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = Thread.ofVirtual().start(
                    () -> serve(server, connectedIds, servedBy, disconnected));

            TwsClientFactory factory = new TwsClientFactory(
                    new TwsConfig().withPort(server.getLocalPort()),
                    ignored -> { }, List.of(1, 2, 3));

            try (TwsSession session = factory.createSession()) {
                assertFalse(factory.isConnected(), "no connection before the first request");
                assertTrue(connectedIds.isEmpty(), "server should have seen no connection yet");

                for (int i = 0; i < 3; i++) {
                    session.submit(TwsRequest.contractDetails(contract("AAPL"))).get(5, TimeUnit.SECONDS);
                }
            }

            awaitSize(connectedIds, 3);
            assertEquals(Set.of(1, 2, 3), Set.copyOf(connectedIds));

            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < 3; i++) {
                seen.add(servedBy.poll(5, TimeUnit.SECONDS));
            }
            assertEquals(Set.of(1, 2, 3), seen, "requests should be spread across the pool");

            factory.close();
            for (int i = 0; i < 3; i++) {
                assertNotNull(disconnected.poll(5, TimeUnit.SECONDS), "connection should be closed");
            }
            serverThread.interrupt();
        }
    }

    @Test
    void unreachableServerFailsOnFirstRequest() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        TwsConfig config = new TwsConfig("127.0.0.1", port, 0, "",
                Duration.ofMillis(200), 1, Duration.ofMillis(10), 100, null);
        TwsClientFactory factory = new TwsClientFactory(config, ignored -> { }, List.of(1));

        try (TwsSession session = factory.createSession()) {
            assertFalse(factory.isConnected());
            assertThrows(UncheckedIOException.class,
                    () -> session.submit(TwsRequest.contractDetails(contract("AAPL"))));
        } finally {
            factory.close();
        }
    }

    @Test
    void createSessionAfterCloseFails() {
        TwsClientFactory factory = new TwsClientFactory(new TwsConfig(), ignored -> { }, List.of(1));
        factory.close();
        assertThrows(IllegalStateException.class, factory::createSession);
    }

    @Test
    void emptyClientIdsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new TwsClientFactory(new TwsConfig(), ignored -> { }, List.of()));
    }

    @Test
    void defaultsToClientIdsOneToThirtyOne() {
        TwsClientFactory factory = new TwsClientFactory(new TwsConfig(), ignored -> { });
        assertEquals(IntStream.range(1, 32).boxed().toList(), factory.clientIds());
        assertEquals(1, factory.clientIds().get(0));
        assertEquals(31, factory.clientIds().get(30));
    }

    @Test
    void closeWithoutUseIsSafe() {
        TwsClientFactory factory = new TwsClientFactory(new TwsConfig(), ignored -> { }, List.of(1));
        factory.close();
        factory.close();
    }

    // ------------------------------------------------------------------ helpers

    private static void awaitSize(List<Integer> ids, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (ids.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, ids.size(), "expected " + expected + " connections, saw " + ids);
    }

    private static ContractProto.Contract contract(String symbol) {
        return ContractProto.Contract.newBuilder()
                .setSymbol(symbol).setSecType("STK").setExchange("SMART").setCurrency("USD")
                .build();
    }

    private static void serve(ServerSocket server, List<Integer> connectedIds,
                              BlockingQueue<Integer> servedBy, BlockingQueue<Integer> disconnected) {
        List<Thread> handlers = new ArrayList<>();
        try {
            while (true) {
                Socket socket = server.accept();
                handlers.add(Thread.ofVirtual().start(
                        () -> handle(socket, connectedIds, servedBy, disconnected)));
            }
        } catch (SocketException e) {
            // server closed
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            for (Thread handler : handlers) {
                handler.interrupt();
            }
        }
    }

    private static void handle(Socket socket, List<Integer> connectedIds,
                               BlockingQueue<Integer> servedBy, BlockingQueue<Integer> disconnected) {
        int clientId = -1;
        try (socket) {
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
                } catch (EOFException | SocketException e) {
                    break;
                }
                byte[] payload = in.readNBytes(length);
                int msgId = Wire.readInt(payload, 0);
                byte[] body = Arrays.copyOfRange(payload, 4, payload.length);

                if (msgId == OutgoingId.START_API.id() + Wire.PROTOBUF_MSG_ID) {
                    clientId = StartApiRequestProto.StartApiRequest.parseFrom(body).getClientId();
                    connectedIds.add(clientId);
                } else if (msgId == OutgoingId.REQ_CONTRACT_DATA.id() + Wire.PROTOBUF_MSG_ID) {
                    int reqId = ContractDataRequestProto.ContractDataRequest.parseFrom(body).getReqId();
                    servedBy.add(clientId);
                    write(out, IncomingId.CONTRACT_DATA,
                            ContractDataProto.ContractData.newBuilder().setReqId(reqId).build());
                    write(out, IncomingId.CONTRACT_DATA_END,
                            ContractDataEndProto.ContractDataEnd.newBuilder().setReqId(reqId).build());
                }
            }
        } catch (IOException e) {
            // connection ended
        } finally {
            if (clientId >= 0) {
                disconnected.add(clientId);
            }
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
