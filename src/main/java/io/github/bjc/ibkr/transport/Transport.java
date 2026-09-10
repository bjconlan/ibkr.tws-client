package io.github.bjc.ibkr.transport;

import io.github.bjc.ibkr.event.IbEvent;
import io.github.bjc.ibkr.protocol.Decoder;
import io.github.bjc.ibkr.protocol.Encoder;
import io.github.bjc.ibkr.protocol.Wire;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the socket and the three virtual threads that keep it moving:
 *
 * <ul>
 *   <li>a <em>writer</em> that drains the outbox so callers never block on socket writes,</li>
 *   <li>a <em>reader</em> that pulls frames off the wire and decodes them,</li>
 *   <li>a <em>dispatcher</em> that invokes the {@link EventHandler} in order.</li>
 * </ul>
 *
 * <p>Splitting these responsibilities means a slow handler cannot stall the reader and cause the
 * server to back up, and a burst of ticks cannot interleave out of order. Virtual threads make
 * the blocking reads and queue takes cheap.
 */
public final class Transport implements AutoCloseable {

    private static final int MAX_MESSAGE_LENGTH = 0xffffff;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final Decoder decoder = new Decoder();

    private final BlockingQueue<byte[]> outbox = new LinkedBlockingQueue<>();
    private final BlockingQueue<IbEvent> inbox = new LinkedBlockingQueue<>();

    private final EventHandler handler;
    private final int serverVersion;
    private final String twsTime;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread writer;
    private final Thread reader;
    private final Thread dispatcher;

    private Transport(Socket socket, DataInputStream in, OutputStream out, EventHandler handler,
                      Handshake handshake) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.handler = handler;
        this.serverVersion = handshake.serverVersion();
        this.twsTime = handshake.twsTime();
        this.writer = Thread.ofVirtual().name("ibkr-writer").start(this::writeLoop);
        this.reader = Thread.ofVirtual().name("ibkr-reader").start(this::readLoop);
        this.dispatcher = Thread.ofVirtual().name("ibkr-dispatch").start(this::dispatchLoop);
    }

    /** The parsed first message from the server. */
    public record Handshake(int serverVersion, String twsTime) {
    }

    /**
     * Connects, performs the {@code API\0 v...} handshake and starts the I/O threads.
     *
     * @throws IOException if the socket cannot be opened or the handshake fails
     */
    public static Transport open(String host, int port, int connectTimeoutMillis, EventHandler handler)
            throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
        socket.setTcpNoDelay(true);
        DataInputStream in = new DataInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();

        out.write(Encoder.connectHeader());
        out.flush();

        byte[] first = readFrame(in);
        if (first == null) {
            socket.close();
            throw new IOException("connection closed during handshake");
        }
        Handshake handshake = parseHandshake(first);
        if (handshake.serverVersion() < Wire.MIN_SERVER_VER_PROTOBUF) {
            socket.close();
            throw new IOException(("server version %d is below the protobuf minimum %d; "
                    + "upgrade TWS/IB Gateway").formatted(handshake.serverVersion(), Wire.MIN_SERVER_VER_PROTOBUF));
        }

        Transport transport = new Transport(socket, in, out, handler, handshake);
        transport.enqueue(new IbEvent.Connected(handshake.serverVersion(), handshake.twsTime()));
        return transport;
    }

    /** Server version reported during the handshake. */
    public int serverVersion() {
        return serverVersion;
    }

    /** Server time string reported during the handshake, may be {@code null}. */
    public String twsTime() {
        return twsTime;
    }

    /** Queues a frame for the writer thread. */
    public void send(byte[] frame) {
        if (!running.get()) {
            throw new IllegalStateException("transport is closed");
        }
        outbox.add(frame);
    }

    public boolean isOpen() {
        return running.get() && !socket.isClosed();
    }

    private static Handshake parseHandshake(byte[] frame) throws IOException {
        if (frame.length < 4) {
            throw new IOException("handshake frame too short: " + frame.length);
        }
        int version = Wire.readInt(frame, 0);
        String time = null;
        if (version >= 20 && frame.length > 4) {
            int end = 4;
            while (end < frame.length && frame[end] != 0) {
                end++;
            }
            time = new String(frame, 4, end - 4, StandardCharsets.UTF_8);
        }
        return new Handshake(version, time);
    }

    private static byte[] readFrame(DataInputStream in) throws IOException {
        int length;
        try {
            length = in.readInt();
        } catch (IOException e) {
            return null;
        }
        if (length <= 0 || length > MAX_MESSAGE_LENGTH) {
            throw new IOException("invalid message length: " + length);
        }
        byte[] payload = new byte[length];
        in.readFully(payload);
        return payload;
    }

    private void enqueue(IbEvent event) {
        inbox.add(event);
    }

    private void writeLoop() {
        try {
            while (running.get()) {
                byte[] frame = outbox.take();
                out.write(frame);
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            terminate("write failed: " + e.getMessage(), e);
        }
    }

    private void readLoop() {
        try {
            while (running.get()) {
                byte[] frame = readFrame(in);
                if (frame == null) {
                    terminate("server closed the connection", null);
                    return;
                }
                enqueueAll(decoder.decode(frame));
            }
        } catch (IOException e) {
            terminate("read failed: " + e.getMessage(), e);
        }
    }

    private void enqueueAll(List<IbEvent> events) {
        for (IbEvent event : events) {
            enqueue(event);
        }
    }

    private void dispatchLoop() {
        try {
            while (running.get()) {
                IbEvent event = inbox.take();
                dispatch(event);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void dispatch(IbEvent event) {
        try {
            handler.onEvent(event);
        } catch (RuntimeException e) {
            // A misbehaving handler must not take down the dispatcher.
            System.getLogger(Transport.class.getName())
                    .log(System.Logger.Level.ERROR, "event handler failed for " + event, e);
        }
    }

    private void terminate(String reason, Throwable cause) {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing is best effort
        }
        writer.interrupt();
        reader.interrupt();
        dispatcher.interrupt();
        dispatch(new IbEvent.Disconnected(reason, cause));
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing is best effort
            }
            writer.interrupt();
            reader.interrupt();
            dispatcher.interrupt();
            dispatch(new IbEvent.Disconnected("closed by client", null));
        }
    }
}
