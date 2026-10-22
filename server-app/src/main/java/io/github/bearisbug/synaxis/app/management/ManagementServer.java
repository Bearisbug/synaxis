package io.github.bearisbug.synaxis.app.management;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.BindAddress;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketAdapter;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.Opcode;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.exceptions.LimitExceededException;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.Handshakedata;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.DefaultWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The management interface of a process ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-032},
 * {@code API-PROD-022}): JSON-RPC 2.0 over WebSocket, on Java-WebSocket ({@code ADR-PROD-014}).
 * <p>
 * The token is checked while the upgrade request is read, so a peer without it never gets a
 * WebSocket and nothing it sends is ever parsed as JSON-RPC; nor does a request carrying
 * {@code Origin}, which every browser sends. The bounds below are this class's, the library sets none
 * of them on its own:
 * <ul>
 * <li>a message longer than {@value #MAX_MESSAGE_BYTES} bytes closes the connection with 1009 before it
 * runs, a fragmented one at the fragment that takes it past the limit;</li>
 * <li>a message in more than {@value #MAX_MESSAGE_FRAGMENTS} fragments, however short, closes it with 1009
 * at the fragment past that count;</li>
 * <li>an upgrade request longer than {@value #MAX_HANDSHAKE_BYTES} bytes, or not finished within
 * {@link #HANDSHAKE_DEADLINE}, closes the socket;</li>
 * <li>a connection accepted while {@value #MAX_UNFINISHED_HANDSHAKES} others have not finished their
 * handshake is closed at once;</li>
 * <li>a message to send that finds {@value #MAX_QUEUED_FRAMES} frames or {@value #MAX_QUEUED_BYTES} bytes
 * queued and not yet written closes the connection with 1008 instead;</li>
 * <li>every {@link #WRITE_RECHECK} a connection with frames queued and no write pending is put back up
 * for writing, which the library's selector thread does not always do once it finished writing a
 * connection's queue.</li>
 * </ul>
 */
public final class ManagementServer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagementServer.class);

    /** The longest message, fragments counted together. */
    static final int MAX_MESSAGE_BYTES = 65_536;
    /** The most fragments of one message, the first one counted. */
    static final int MAX_MESSAGE_FRAGMENTS = 1_024;
    /** The longest upgrade request: a handshake with its token is well under 1 KiB. */
    static final int MAX_HANDSHAKE_BYTES = 8_192;
    /** How long a connection has to finish its upgrade request before its socket is closed. */
    static final Duration HANDSHAKE_DEADLINE = Duration.ofSeconds(5);
    /** The connections whose handshake is not over at which one more is closed as it is accepted. */
    static final int MAX_UNFINISHED_HANDSHAKES = 64;
    /** The frames queued and not yet written at which a connection takes no more messages. */
    static final int MAX_QUEUED_FRAMES = 4_096;
    /** The bytes queued and not yet written at which a connection takes no more messages. */
    static final int MAX_QUEUED_BYTES = 1_048_576;
    /** How often the connections are looked at for frames queued with no write pending. */
    static final Duration WRITE_RECHECK = Duration.ofMillis(100);
    /**
     * Java-WebSocket's decoder threads, shared by every connection. One is enough because every call runs on
     * a {@code Syn-Management-Call} virtual thread of its own ({@code onMessage}), so a decoder never waits
     * on the database.
     */
    private static final int DECODER_THREADS = 1;
    private static final Duration BIND_TIMEOUT = Duration.ofSeconds(10);
    private static final int CLOSE_TIMEOUT_MILLIS = 1_000;

    static final String JOINED = "minecraft:notification/players/joined";
    static final String LEFT = "minecraft:notification/players/left";

    private final @Nullable Endpoint endpoint;

    private ManagementServer(@Nullable Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * Serves the management interface when the settings enable it, before the game port opens.
     *
     * @param settings where and whether to serve
     * @param process  the engine process, whose players are announced to the connections
     * @param methods  what the connections may call
     * @return the interface, which does nothing when disabled
     * @throws IllegalArgumentException if the address cannot be bound, which refuses the start
     */
    public static ManagementServer serve(ManagementSettings settings, ServerProcess process, ManagementMethods methods) {
        return serve(settings, process, methods, HANDSHAKE_DEADLINE);
    }

    static ManagementServer serve(ManagementSettings settings, ServerProcess process, ManagementMethods methods,
                                  Duration handshakeDeadline) {
        if (!settings.enabled()) {
            LOGGER.info("Effective management: {}", settings);
            return new ManagementServer(null);
        }
        final Endpoint endpoint = new Endpoint(settings, methods, handshakeDeadline);
        endpoint.start();
        try {
            endpoint.bound.get(BIND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            endpoint.shutDown();
            final Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            throw new IllegalArgumentException(ManagementSettings.BIND_PROPERTY + "="
                    + BindAddress.format(settings.bind()) + " cannot be bound: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            endpoint.shutDown();
            throw new IllegalArgumentException("interrupted while binding the management interface", e);
        }
        announcePlayers(endpoint, process);
        LOGGER.info("Effective management: {}", settings);
        final InetSocketAddress listening = InetSocketAddress.createUnresolved(settings.bind().getHostString(),
                endpoint.getPort());
        LOGGER.info("Management interface listening on {}", BindAddress.format(listening));
        return new ManagementServer(endpoint);
    }

    /**
     * A player who entered the world is announced as joined; one announced as joined is announced
     * as left when leaving, one who never got into the world is announced neither way.
     */
    private static void announcePlayers(Endpoint endpoint, ServerProcess process) {
        final Set<Player> joined = ConcurrentHashMap.newKeySet();
        process.eventHandler().addListener(PlayerSpawnEvent.class, event -> {
            if (event.isFirstSpawn() && joined.add(event.getPlayer()))
                endpoint.broadcast(ManagementMethods.playerNotification(JOINED, event.getPlayer()));
        });
        process.eventHandler().addListener(PlayerDisconnectEvent.class, event -> {
            if (joined.remove(event.getPlayer()))
                endpoint.broadcast(ManagementMethods.playerNotification(LEFT, event.getPlayer()));
        });
    }

    /**
     * Returns where the interface listens.
     *
     * @return the address, null when the interface is disabled
     */
    public @Nullable InetSocketAddress address() {
        final Endpoint endpoint = this.endpoint;
        return endpoint == null ? null : new InetSocketAddress(endpoint.getAddress().getAddress(), endpoint.getPort());
    }

    /** Closes every connection with 1001 and stops listening; a shutdown task. */
    @Override
    public void close() {
        if (endpoint != null) endpoint.shutDown();
    }

    private static final class Endpoint extends WebSocketServer {
        private final ManagementSettings settings;
        private final ManagementMethods methods;
        private final LogBudget failures = new LogBudget(LOGGER, "management connection errors");
        private final LogBudget backlogs = new LogBudget(LOGGER, "management connections closed for their backlog");
        private final LogBudget crowded = new LogBudget(LOGGER, "management connections closed at once");
        final CompletableFuture<Void> bound = new CompletableFuture<>();
        private final Thread writeRecheck = Thread.ofVirtual().name("Syn-Management-Writes").unstarted(this::recheckWrites);

        Endpoint(ManagementSettings settings, ManagementMethods methods, Duration handshakeDeadline) {
            super(settings.bind(), DECODER_THREADS, List.of(new BoundedDraft()));
            this.settings = settings;
            this.methods = methods;
            setDaemon(true);
            setReuseAddr(true);
            setWebSocketFactory(new GuardedFactory(handshakeDeadline, backlogs, crowded));
        }

        @Override
        public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft,
                                                                           ClientHandshake request)
                throws InvalidDataException {
            // Refused here, the library answers 404 and closes: no WebSocket, no frame is read.
            // A browser sends Origin on every WebSocket, an operator's tool does not; with the token
            // added by a reverse proxy, any page the operator opens could otherwise connect.
            if (request.hasFieldValue("Origin"))
                throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "origin");
            if (!settings.accepts(request.getFieldValue("Authorization")))
                throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "unauthorized");
            return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
        }

        @Override
        public void onStart() {
            writeRecheck.start();
            bound.complete(null);
        }

        /**
         * Java-WebSocket's selector thread, once it has written out the queue of a connection, stops
         * waiting to write to it without looking at the queue again. A frame queued between its last
         * look and that step, a pong by the decoder thread or a reply by a call, stays queued with no
         * write pending until something else is queued on that connection, for the last reply to a
         * quiet peer the ping of the lost connection timer up to 60 s later. This puts such a
         * connection back up for writing within {@link #WRITE_RECHECK}.
         */
        private void recheckWrites() {
            try {
                while (true) {
                    Thread.sleep(WRITE_RECHECK);
                    for (WebSocket conn : getConnections()) {
                        final WebSocketImpl socket = (WebSocketImpl) conn;
                        try {
                            if (!socket.outQueue.isEmpty()
                                    && (socket.getSelectionKey().interestOps() & SelectionKey.OP_WRITE) == 0)
                                onWriteDemand(socket);
                        } catch (CancelledKeyException _) {
                            // Closed meanwhile: nothing more is written to it
                        }
                    }
                }
            } catch (InterruptedException _) {
                // The interface is stopping
            }
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            // Nothing to set up: every message carries all it needs
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            // Off the decoder thread: a save waits for the database, a recording for its seconds
            Thread.ofVirtual().name("Syn-Management-Call").start(() -> {
                final ManagementMethods.Reply reply = methods.handle(message);
                if (reply.text() != null) {
                    try {
                        conn.send(reply.text());
                    } catch (WebsocketNotConnectedException _) {
                        // The caller left before the answer was ready; the call itself is done
                    }
                }
                if (reply.afterReply() != null) reply.afterReply().run();
            });
        }

        @Override
        public void onMessage(WebSocket conn, ByteBuffer message) {
            conn.close(CloseFrame.REFUSE, "text frames only");
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            // Nothing held per connection
        }

        @Override
        public void onError(@Nullable WebSocket conn, Exception ex) {
            if (!bound.isDone()) {
                bound.completeExceptionally(ex);
                return;
            }
            failures.warn(ex, "Management interface error: remote={}",
                    conn == null ? null : conn.getRemoteSocketAddress());
        }

        void shutDown() {
            writeRecheck.interrupt();
            try {
                stop(CLOSE_TIMEOUT_MILLIS, "the server is stopping");
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * RFC 6455 with the length of a fragmented message checked at every fragment. Java-WebSocket
     * checks it at the first and the last fragment only and keeps every fragment in between, so a
     * peer that never sends the last one would have it buffer until the heap is full. The count of
     * fragments is held too: the library keeps a buffer for every fragment however short, and a
     * peer sending empty ones never adds to the length. Outbound, it adds up the bytes of every
     * buffer it makes for the queue, which is how {@link GuardedSocket} knows what is queued.
     */
    private static final class BoundedDraft extends Draft_6455 {
        // Each connection gets its own copy, read by that connection's one decoder thread: the bytes
        // and the fragments of the message whose fragments are coming in
        private long messageBytes;
        private int messageFragments;
        /** The handshake answer and every frame made to be queued, from any sending thread. */
        final AtomicLong madeBytes = new AtomicLong();

        BoundedDraft() {
            super(List.of(), MAX_MESSAGE_BYTES);
        }

        @Override
        public Draft copyInstance() {
            return new BoundedDraft();
        }

        @Override
        public void processFrame(WebSocketImpl conn, Framedata frame) throws InvalidDataException {
            final Opcode opcode = frame.getOpcode();
            if (opcode == Opcode.TEXT || opcode == Opcode.BINARY) {
                messageBytes = frame.getPayloadData().remaining();
                messageFragments = 1;
            } else if (opcode == Opcode.CONTINUOUS) {
                messageBytes += frame.getPayloadData().remaining();
                messageFragments++;
            }
            if (messageBytes > MAX_MESSAGE_BYTES) throw new LimitExceededException(MAX_MESSAGE_BYTES);
            if (messageFragments > MAX_MESSAGE_FRAGMENTS) throw new LimitExceededException(MAX_MESSAGE_FRAGMENTS);
            super.processFrame(conn, frame);
        }

        // The library queues exactly what these two return, each once

        @Override
        public ByteBuffer createBinaryFrame(Framedata framedata) {
            final ByteBuffer frame = super.createBinaryFrame(framedata);
            madeBytes.addAndGet(frame.remaining());
            return frame;
        }

        @Override
        public List<ByteBuffer> createHandshake(Handshakedata handshakedata, boolean withcontent) {
            final List<ByteBuffer> handshake = super.createHandshake(handshakedata, withcontent);
            for (ByteBuffer buffer : handshake) madeBytes.addAndGet(buffer.remaining());
            return handshake;
        }
    }

    /** Puts the bounds on the handshake and on the outbound queue that Java-WebSocket does not have. */
    private static final class GuardedFactory extends DefaultWebSocketServerFactory {
        private final Duration deadline;
        private final LogBudget backlogs;
        private final LogBudget crowded;
        /** The connections counted against {@link #MAX_UNFINISHED_HANDSHAKES}, some maybe finished since. */
        private final Set<GuardedSocket> unfinished = ConcurrentHashMap.newKeySet();

        GuardedFactory(Duration deadline, LogBudget backlogs, LogBudget crowded) {
            this.deadline = deadline;
            this.backlogs = backlogs;
            this.crowded = crowded;
        }

        @Override
        public WebSocketImpl createWebSocket(WebSocketAdapter adapter, Draft draft) {
            return guarded(new GuardedSocket(adapter, List.of(draft), backlogs, crowded));
        }

        @Override
        public WebSocketImpl createWebSocket(WebSocketAdapter adapter, List<Draft> drafts) {
            return guarded(new GuardedSocket(adapter, drafts, backlogs, crowded));
        }

        /**
         * Called by the selector thread as it accepts a connection, the only thread that adds to
         * {@link #unfinished}: a connection opened, refused or closed since it was counted is no
         * longer {@code NOT_YET_CONNECTED} and stops counting here.
         */
        private WebSocketImpl guarded(GuardedSocket socket) {
            unfinished.removeIf(counted -> counted.getReadyState() != ReadyState.NOT_YET_CONNECTED);
            if (unfinished.size() >= MAX_UNFINISHED_HANDSHAKES) {
                socket.closeOnceAccepted = true;
                return socket;
            }
            unfinished.add(socket);
            Thread.ofVirtual().name("Syn-Management-Handshake").start(() -> {
                try {
                    Thread.sleep(deadline);
                } catch (InterruptedException _) {
                    return;
                }
                if (socket.getReadyState() == ReadyState.NOT_YET_CONNECTED)
                    socket.closeConnection(CloseFrame.NEVER_CONNECTED, "no handshake within " + deadline.toMillis() + " ms");
            });
            return socket;
        }
    }

    /**
     * A connection that stops reading a handshake grown past {@link #MAX_HANDSHAKE_BYTES}, and that
     * is closed rather than queue a message behind {@link #MAX_QUEUED_FRAMES} frames or
     * {@link #MAX_QUEUED_BYTES} bytes its peer has not read. Java-WebSocket queues every outbound
     * frame without a bound until the socket takes it, so a peer that never reads would have the
     * replies to its calls and the pongs to its pings pile up until the heap is full.
     */
    private static final class GuardedSocket extends WebSocketImpl {
        private final LogBudget backlogs;
        private final LogBudget crowded;
        private final AtomicBoolean closedForBacklog = new AtomicBoolean();
        /** What the selector thread has written to the socket, the handshake answer included. */
        private final AtomicLong writtenBytes = new AtomicLong();
        // Only the one decoder thread of this connection touches it
        private int handshakeBytes;
        /** Accepted past {@link #MAX_UNFINISHED_HANDSHAKES}; set and read by the selector thread. */
        boolean closeOnceAccepted;

        GuardedSocket(WebSocketAdapter adapter, List<Draft> drafts, LogBudget backlogs, LogBudget crowded) {
            super(adapter, drafts);
            this.backlogs = backlogs;
            this.crowded = crowded;
        }

        /**
         * Set once, when the connection is accepted; every write to the socket goes through it. A
         * connection accepted past {@link #MAX_UNFINISHED_HANDSHAKES} is closed here, before it reads.
         */
        @Override
        public void setChannel(ByteChannel channel) {
            super.setChannel(new ByteChannel() {
                @Override
                public int read(ByteBuffer destination) throws IOException {
                    return channel.read(destination);
                }

                @Override
                public int write(ByteBuffer source) throws IOException {
                    final int written = channel.write(source);
                    writtenBytes.addAndGet(written);
                    return written;
                }

                @Override
                public boolean isOpen() {
                    return channel.isOpen();
                }

                @Override
                public void close() throws IOException {
                    channel.close();
                }
            });
            if (closeOnceAccepted) {
                crowded.warn("Management connection closed at once: remote={} arrived with {} handshakes unfinished",
                        getRemoteSocketAddress(), MAX_UNFINISHED_HANDSHAKES);
                closeConnection(CloseFrame.NEVER_CONNECTED, MAX_UNFINISHED_HANDSHAKES + " handshakes unfinished");
            }
        }

        // Every message sent after the handshake goes through one of these four methods: the replies,
        // the notifications, the pongs, the pings of the lost connection timer and the close frame.
        // Each one is judged and queued under this connection's monitor: the replies come from one
        // virtual thread per call, and two that passed the judgment before either had queued would
        // take the queue past its bound by more than one message. It is the library's own lock, held
        // by close while it sends the close frame and taken by closeConnection, so that a sender
        // closing the connection from admits never waits on a close waiting on it.

        @Override
        public void send(String text) {
            synchronized (this) {
                if (admits()) super.send(text);
            }
        }

        @Override
        public void send(ByteBuffer bytes) {
            synchronized (this) {
                if (admits()) super.send(bytes);
            }
        }

        @Override
        public void sendFrame(Framedata frame) {
            synchronized (this) {
                if (admits()) super.sendFrame(frame);
            }
        }

        @Override
        public void sendFrame(Collection<Framedata> frames) {
            synchronized (this) {
                if (admits()) super.sendFrame(frames);
            }
        }

        /**
         * Whether one more message may be queued; if not, the connection is closed with 1008 at once,
         * without a close frame, which would only wait behind what the peer does not read. Both
         * figures are counters: walking the queue takes its locks once per buffer, and thousands
         * of calls answering at once would queue up behind each other on them. Called with this
         * connection's monitor held, by the four senders above.
         */
        private boolean admits() {
            if (closedForBacklog.get()) return false;
            final int frames = outQueue.size();
            final long bytes = ((BoundedDraft) getDraft()).madeBytes.get() - writtenBytes.get();
            if (frames < MAX_QUEUED_FRAMES && bytes < MAX_QUEUED_BYTES) return true;
            if (closedForBacklog.compareAndSet(false, true)) {
                backlogs.warn("Management connection closed with 1008: remote={} has {} frames and {} bytes queued "
                        + "that it does not read", getRemoteSocketAddress(), frames, bytes);
                closeConnection(CloseFrame.POLICY_VALIDATION, "outbound backlog");
                outQueue.clear();
            }
            return false;
        }

        @Override
        public void decode(ByteBuffer buffer) {
            if (getReadyState() == ReadyState.NOT_YET_CONNECTED) {
                handshakeBytes += buffer.remaining();
                if (handshakeBytes > MAX_HANDSHAKE_BYTES) {
                    closeConnection(CloseFrame.TOOBIG, "handshake longer than " + MAX_HANDSHAKE_BYTES + " bytes");
                    return;
                }
            }
            super.decode(buffer);
        }
    }
}
