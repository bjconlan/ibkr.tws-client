package io.github.bjconlan.ibkr.event;

/**
 * Everything the server can tell the client, as one sealed hierarchy.
 *
 * <p>Server messages are carried verbatim as their generated protobuf payload inside
 * {@link Message}, so every message the protocol defines is representable without a parallel
 * record type. A handler narrows to the concrete generated class:
 *
 * <pre>{@code
 * switch (event) {
 *     case IbEvent.Message m when m.payload() instanceof TickPriceProto.TickPrice p ->
 *         render(p.getReqId(), p.getPrice());
 *     case IbEvent.Message m when m.payload() instanceof ErrorMessageProto.ErrorMessage e ->
 *         log.error("{}: {}", e.getErrorCode(), e.getErrorMsg());
 *     case IbEvent.Connected c  -> log.info("server {}", c.serverVersion());
 *     case IbEvent.Disconnected d -> log.warn("gone: {}", d.reason());
 *     case IbEvent.Error e -> log.error("{}: {}", e.code(), e.message());
 *     default -> { }
 * }
 * }</pre>
 *
 * <p>The three lifecycle records are synthesised by the transport and handshake; everything
 * else arrives as {@link Message}. Events are delivered in the order the server produced them
 * by a single dispatcher thread, so tick streams stay coherent even when the handler blocks.
 */
public sealed interface IbEvent {

    /** Handshake completed; {@code serverVersion} gates which message variants are in use. */
    record Connected(int serverVersion, String twsTime) implements IbEvent {}

    /** The connection ended, either locally or because the socket failed. */
    record Disconnected(String reason, Throwable cause) implements IbEvent {}

    /** An error or informational message from TWS. Informational codes have {@code code < 2100}. */
    record Error(int requestId, long epochMillis, int code, String message,
                 String advancedOrderRejectJson) implements IbEvent {}

    /**
     * A server message, carried as its generated protobuf payload.
     *
     * @param id      the base {@link io.github.bjconlan.ibkr.protocol.IncomingId} value
     * @param payload the concrete generated message ({@code TickPriceProto.TickPrice}, ...)
     */
    record Message(int id, com.google.protobuf.Message payload) implements IbEvent {}
}
