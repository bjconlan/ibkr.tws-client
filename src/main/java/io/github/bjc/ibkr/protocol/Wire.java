package io.github.bjc.ibkr.protocol;

import com.google.protobuf.MessageLite;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Framing constants and helpers for the TWS "V100+" envelope. */
public final class Wire {

    private Wire() {
    }

    /** Lowest envelope version this client advertises. */
    public static final int MIN_VERSION = 100;

    /** Highest envelope version this client advertises. */
    public static final int MAX_VERSION = 226;

    /**
     * Server version at which TWS switched the listed messages to protobuf and switched
     * message ids from null-terminated ASCII to fixed-width big-endian ints.
     */
    public static final int MIN_SERVER_VER_PROTOBUF = 201;

    /** Added to a base {@link OutgoingId}/{@link IncomingId} to mark the payload as protobuf. */
    public static final int PROTOBUF_MSG_ID = 200;

    /** Every frame carries a 4-byte big-endian length that excludes the length field itself. */
    public static final int LENGTH_PREFIX = 4;

    /** The {@code API\0} preamble sent before the version string on connect. */
    public static final byte[] API_PREAMBLE = "API\0".getBytes(StandardCharsets.US_ASCII);

    /**
     * Builds a frame: {@code [length][msgId][protobuf payload]}, where {@code length} counts the
     * id and the payload. Used by both the client encoder and the test server.
     */
    public static byte[] frame(OutgoingId id, MessageLite body) {
        return frameId(id.id() + PROTOBUF_MSG_ID, body);
    }

    /** As {@link #frame(OutgoingId, MessageLite)} but for a server-to-client message. */
    public static byte[] frame(IncomingId id, MessageLite body) {
        return frameId(id.id() + PROTOBUF_MSG_ID, body);
    }

    private static byte[] frameId(int wireId, MessageLite body) {
        byte[] payload = body.toByteArray();
        ByteBuffer out = ByteBuffer.allocate(2 * LENGTH_PREFIX + payload.length).order(ByteOrder.BIG_ENDIAN);
        out.putInt(LENGTH_PREFIX + payload.length);
        out.putInt(wireId);
        out.put(payload);
        return out.array();
    }

    /** Reads a big-endian int from {@code bytes} at {@code offset}. */
    public static int readInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }
}
