package io.github.bjconlan.ibkr;

import io.github.bjconlan.ibkr.event.IbEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A scoped view over a {@link TwsConnection} through which requests are issued and owned. The
 * connection allocates request ids, applies pacing and routes responses; the session only decides
 * how the responses are consumed and guarantees they are cancelled when the session closes.
 *
 * <p>Three consumption shapes, one entry point each:
 * <ul>
 *   <li>{@link #submit(TwsRequest)} - bounded; a {@link CompletableFuture} of the correlated
 *       responses, completed on the terminal message.</li>
 *   <li>{@link #stream(TwsRequest)} - pull; a JDK {@link Stream} over the responses that ends on
 *       the terminal message and cancels when closed.</li>
 *   <li>{@link #on(TwsRequest, Consumer)} - push; the handler is invoked on the connection's
 *       dispatcher thread until the returned handle is closed.</li>
 * </ul>
 *
 * <p>A session is a lifetime scope, not a connection lease: closing it cancels every request
 * opened through it and leaves the connection usable. Pass a session across function calls to give
 * those calls a shared, cancellable request scope.
 */
public final class TwsSession implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(TwsSession.class.getName());

    private final Supplier<TwsConnection> connections;
    private final Set<RequestHandle> handles = ConcurrentHashMap.newKeySet();

    /**
     * Internal: creates a session that draws connections from {@code connections}. Obtain one from
     * {@link TwsConnection#createSession()} or {@link TwsClientFactory#createSession()}.
     */
    TwsSession(Supplier<TwsConnection> connections) {
        this.connections = connections;
    }

    /**
     * Submits a bounded request and returns a future of every correlated response, completed on
     * the terminal message. Cancelling the future cancels the request upstream.
     *
     * @throws IllegalArgumentException if the request is open-ended; use {@link #stream} or
     *                                  {@link #on} instead
     */
    public CompletableFuture<List<IbEvent.Message>> submit(TwsRequest request) {
        if (!request.isBounded()) {
            throw new IllegalArgumentException(
                    request.id() + " is open-ended; use stream(...) or on(...) instead");
        }
        CompletableFuture<List<IbEvent.Message>> future = new CompletableFuture<>();
        List<IbEvent.Message> items = Collections.synchronizedList(new ArrayList<>());
        RequestHandle handle = open(request, new RequestSink() {
            @Override
            public void message(IbEvent.Message message) {
                items.add(message);
            }

            @Override
            public void terminal() {
                future.complete(List.copyOf(items));
            }

            @Override
            public void error(Throwable cause) {
                future.completeExceptionally(cause);
            }

            @Override
            public void cancelled() {
                future.cancel(false);
            }
        });
        future.whenComplete((ignored, error) -> {
            if (error instanceof CancellationException) {
                handle.close();
            }
        });
        return future;
    }

    /**
     * Submits a request and returns its responses as a JDK {@link Stream}: that is, synchronous,
     * pull-based and {@link Stream#close() auto-closable}. Iteration parks until the next response
     * and ends on the terminal message; closing the stream cancels the request and releases any
     * held permit.
     *
     * <p>The source is a bounded queue, so the connection's dispatcher never blocks; on overflow
     * the oldest buffered response is dropped.
     */
    public Stream<IbEvent.Message> stream(TwsRequest request) {
        return stream(request, Flow.defaultBufferSize());
    }

    /** As {@link #stream(TwsRequest)} with an explicit buffer capacity. */
    public Stream<IbEvent.Message> stream(TwsRequest request, int bufferCapacity) {
        BlockingQueue<StreamItem> queue = new ArrayBlockingQueue<>(Math.max(1, bufferCapacity));
        RequestHandle handle = open(request, new RequestSink() {
            @Override
            public void message(IbEvent.Message message) {
                StreamItem item = StreamItem.of(message);
                if (!queue.offer(item)) {
                    queue.poll();
                    queue.offer(item);
                }
            }

            @Override
            public void terminal() {
                queue.offer(StreamItem.end(null));
            }

            @Override
            public void error(Throwable cause) {
                queue.offer(StreamItem.end(cause));
            }

            @Override
            public void cancelled() {
                queue.offer(StreamItem.end(null));
            }
        });
        Iterator<IbEvent.Message> iterator = new QueueIterator(queue);
        Stream<IbEvent.Message> stream = StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL),
                false);
        return stream.onClose(handle::close);
    }

    /**
     * Submits a request and invokes {@code handler} for each correlated response on the
     * connection's dispatcher thread. Close the returned handle to cancel the request.
     *
     * <p>The handler runs on the dispatcher, so it must not block; use {@link #stream} when you
     * would rather park your own thread.
     */
    public AutoCloseable on(TwsRequest request, Consumer<IbEvent.Message> handler) {
        return open(request, new RequestSink() {
            @Override
            public void message(IbEvent.Message message) {
                handler.accept(message);
            }

            @Override
            public void terminal() {
            }

            @Override
            public void error(Throwable cause) {
            }

            @Override
            public void cancelled() {
            }
        });
    }

    /** Cancels every request opened through this session. The connection stays open. */
    @Override
    public void close() {
        if (!handles.isEmpty()) {
            LOG.log(System.Logger.Level.DEBUG,
                    "closing session with %d open request(s)".formatted(handles.size()));
        }
        for (RequestHandle handle : handles) {
            handle.close();
        }
        handles.clear();
    }

    private RequestHandle open(TwsRequest request, RequestSink sink) {
        TwsConnection connection = connections.get();
        RequestHandle handle = connection.create(request, sink, handles::remove);
        handles.add(handle);
        try {
            connection.start(handle);
        } catch (RuntimeException e) {
            handles.remove(handle);
            throw e;
        }
        LOG.log(System.Logger.Level.TRACE, "submitted %s as request %d".formatted(request.id(), handle.reqId()));
        return handle;
    }

    private record StreamItem(IbEvent.Message message, Throwable error, boolean end) {

        static StreamItem of(IbEvent.Message message) {
            return new StreamItem(message, null, false);
        }

        static StreamItem end(Throwable error) {
            return new StreamItem(null, error, true);
        }
    }

    private static final class QueueIterator implements Iterator<IbEvent.Message> {

        private final BlockingQueue<StreamItem> queue;
        private IbEvent.Message next;
        private boolean done;

        QueueIterator(BlockingQueue<StreamItem> queue) {
            this.queue = queue;
        }

        @Override
        public boolean hasNext() {
            if (next != null) {
                return true;
            }
            if (done) {
                return false;
            }
            try {
                StreamItem item = queue.take();
                if (item.end()) {
                    done = true;
                    if (item.error() != null) {
                        throw new CompletionException(item.error());
                    }
                    return false;
                }
                next = item.message();
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                done = true;
                return false;
            }
        }

        @Override
        public IbEvent.Message next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            IbEvent.Message result = next;
            next = null;
            return result;
        }
    }
}
