package io.github.bjconlan.ibkr;

import com.google.protobuf.MessageLite;
import io.github.bjconlan.ibkr.event.IbEvent;
import io.github.bjconlan.ibkr.pacing.Pacer;
import io.github.bjconlan.ibkr.protocol.OutgoingId;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Internal: one in-flight request on a {@link TwsConnection}. Owns the request id, the held
 * permit, and the completion handshake with the {@link RequestSink}. Idempotent: terminal, failure
 * and local cancel race safely and only the first wins.
 */
final class RequestHandle implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(RequestHandle.class.getName());

    private final TwsConnection connection;
    private final int reqId;
    private final TwsRequest request;
    private final RequestSink sink;
    private final Consumer<RequestHandle> onDone;
    private final AtomicBoolean done = new AtomicBoolean();

    private Pacer.Lease lease;

    RequestHandle(TwsConnection connection, int reqId, TwsRequest request,
                  RequestSink sink, Consumer<RequestHandle> onDone) {
        this.connection = connection;
        this.reqId = reqId;
        this.request = request;
        this.sink = sink;
        this.onDone = onDone;
    }

    int reqId() {
        return reqId;
    }

    TwsRequest request() {
        return request;
    }

    void lease(Pacer.Lease lease) {
        this.lease = lease;
    }

    /** Routes one correlated response to the sink. */
    void message(IbEvent.Message message) {
        sink.message(message);
    }

    /** The terminal message arrived; release without cancelling. */
    void terminal() {
        if (done.compareAndSet(false, true)) {
            connection.forget(reqId);
            release();
            sink.terminal();
            onDone.accept(this);
            LOG.log(System.Logger.Level.TRACE, "request %d (%s) terminal".formatted(reqId, request.id()));
        }
    }

    /** The request failed. */
    void fail(Throwable cause) {
        if (done.compareAndSet(false, true)) {
            connection.forget(reqId);
            release();
            sink.error(cause);
            onDone.accept(this);
            LOG.log(System.Logger.Level.DEBUG,
                    "request %d (%s) failed".formatted(reqId, request.id()), cause);
        }
    }

    /** Cancel locally: tell the server if the request has a cancel message, then release. */
    @Override
    public void close() {
        if (done.compareAndSet(false, true)) {
            connection.forget(reqId);
            sendCancel();
            release();
            sink.cancelled();
            onDone.accept(this);
            LOG.log(System.Logger.Level.TRACE, "request %d (%s) cancelled".formatted(reqId, request.id()));
        }
    }

    private void sendCancel() {
        MessageLite body = request.cancel() == null ? null : request.cancel().apply(reqId);
        OutgoingId cancelId = request.cancelId();
        if (body != null && cancelId != null) {
            connection.sendIfOpen(cancelId, body);
        }
    }

    private void release() {
        if (lease != null) {
            lease.close();
        }
    }
}
