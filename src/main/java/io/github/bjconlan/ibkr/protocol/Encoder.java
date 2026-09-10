package io.github.bjconlan.ibkr.protocol;

import com.google.protobuf.MessageLite;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Serialises outbound requests. Every request is framed as
 * {@code [length][msgId + 200][protobuf payload]}; the caller supplies the generated message,
 * so no per-request encoder is needed.
 */
public final class Encoder {

    private Encoder() {
    }

    /**
     * The handshake written before any framed message: {@code API\0} followed by a
     * length-prefixed version range such as {@code v100..226}.
     */
    public static byte[] connectHeader() {
        byte[] version = ("v" + Wire.MIN_VERSION + ".." + Wire.MAX_VERSION).getBytes(StandardCharsets.US_ASCII);
        ByteBuffer out = ByteBuffer.allocate(Wire.API_PREAMBLE.length + 4 + version.length)
                .order(ByteOrder.BIG_ENDIAN);
        out.put(Wire.API_PREAMBLE);
        out.putInt(version.length);
        out.put(version);
        return out.array();
    }

    /** Frames any outbound request. */
    public static byte[] encode(OutgoingId id, MessageLite body) {
        return Wire.frame(id, body);
    }
}
