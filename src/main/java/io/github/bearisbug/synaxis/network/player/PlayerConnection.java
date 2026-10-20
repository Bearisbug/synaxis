package io.github.bearisbug.synaxis.network.player;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.crypto.PlayerPublicKey;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.OutgoingTransferEvent;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.monitoring.EventsJFR;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.CookieRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.CookieStorePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.TransferPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.SelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginDisconnectPacket;
import io.github.bearisbug.synaxis.network.plugin.LoginPluginMessageProcessor;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A PlayerConnection is an object needed for all created {@link Player}.
 * It can be extended to create a new kind of player (NPC for instance).
 */
public abstract class PlayerConnection {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerConnection.class);

    // Correlation id of every log line and disconnect packet of this connection
    private final UUID connectionId = UUID.randomUUID();
    private final long connectedAtMillis = System.currentTimeMillis();

    private Player player;

    // Server & client states can differ during configuration.
    // "server" state means the state the server thinks its in.
    // "client" state means the state the client thinks its in.
    // For example, after sending start configuration but before receiving the ack,
    // the server will be in CONFIGURATION while the client is still in PLAY.
    private volatile ConnectionState serverState, clientState;

    private @Nullable PlayerPublicKey playerPublicKey;
    private final AtomicBoolean loginStarted = new AtomicBoolean();
    private boolean statusRequested;
    private boolean statusPinged;
    volatile boolean online;
    private volatile boolean wasTransferred;
    private volatile boolean turnedAway;

    @SuppressWarnings("this-escape") // deliberate self registration during construction
    private @Nullable LoginPluginMessageProcessor loginPluginMessageProcessor = new LoginPluginMessageProcessor(this);

    // Present only when waiting for a response from the client. Written by the configuration thread
    // and cleared by either the packet reader or the disconnection, hence volatile.
    private volatile @Nullable CompletableFuture<List<SelectKnownPacksPacket.Entry>> knownPacksFuture = null;

    private final Map<Key, CompletableFuture<byte @Nullable []>> pendingCookieRequests = new ConcurrentHashMap<>();

    private final ServerProcess process;

    public PlayerConnection(ServerProcess process) {
        this.process = process;
        this.online = true;
        this.serverState = ConnectionState.HANDSHAKE;
        this.clientState = ConnectionState.HANDSHAKE;
    }

    /**
     * Returns the correlation id assigned to this connection when the client was accepted.
     *
     * @return the process-unique connection id
     */
    public UUID connectionId() {
        return connectionId;
    }

    /**
     * Returns whether this connection is still waiting to reach the configuration state
     * past the login timeout.
     *
     * @param nowMillis the current wall clock time in milliseconds
     * @return true if the connection ran out of time to log in
     */
    @ApiStatus.Internal
    public boolean loginTimedOut(long nowMillis) {
        // The watchdog sleeps exactly one login timeout before asking, so the deadline itself has
        // to count as expired: milliseconds are integers and it only ever asks once.
        // The client state is the one the peer has to reach itself, by acknowledging the login. The
        // server reaches configuration on its own the moment it writes the login success, which a
        // peer that goes silent right after its login start would get for free.
        return online
                && clientState.ordinal() < ConnectionState.CONFIGURATION.ordinal()
                && nowMillis - connectedAtMillis >= process.config().loginTimeout();
    }

    /**
     * When this connection runs out of the login timeout, in {@link System#currentTimeMillis()}
     * time: the budget a session server query of the login has to fit in.
     */
    @ApiStatus.Internal
    public long loginDeadlineMillis() {
        return connectedAtMillis + process.config().loginTimeout();
    }

    /**
     * Returns whether this connection is still waiting for its handshake past
     * {@code synaxis.handshake-timeout-ms}.
     *
     * @param nowMillis the current wall clock time in milliseconds
     * @return true if the connection ran out of time to send its handshake
     */
    @ApiStatus.Internal
    public boolean handshakeTimedOut(long nowMillis) {
        // Same as the login timeout: the deadline itself counts as expired
        return online
                && clientState == ConnectionState.HANDSHAKE
                && nowMillis - connectedAtMillis >= process.config().handshakeTimeout();
    }

    /**
     * Claims the single login this connection is allowed to start. Repeating the login start packet
     * does not change the connection state, so without this the peer can run the whole login again
     * for every copy of it.
     *
     * @return true the first time it is called, false for every repeat
     */
    @ApiStatus.Internal
    public boolean claimLoginStart() {
        return loginStarted.compareAndSet(false, true);
    }

    /**
     * Returns a printable identifier for this connection, will be the player username
     * or the connection remote address.
     *
     * @return this connection identifier
     */
    public String getIdentifier() {
        final Player player = getPlayer();
        return player != null ?
                player.getUsername() :
                getRemoteAddress().toString();
    }

    /**
     * Serializes the packet and send it to the client.
     *
     * @param packet the packet to send
     */
    public abstract void sendPacket(SendablePacket packet);

    public void sendPackets(Collection<? extends SendablePacket> packets) {
        packets.forEach(this::sendPacket);
    }

    public void sendPackets(SendablePacket... packets) {
        sendPackets(List.of(packets));
    }

    /**
     * Gets the remote address of the client.
     *
     * @return the remote address
     */
    public abstract SocketAddress getRemoteAddress();

    /**
     * Gets protocol version of client.
     *
     * @return the protocol version
     */
    public int getProtocolVersion() {
        return MinecraftServer.PROTOCOL_VERSION;
    }

    /**
     * Gets the server process this connection belongs to.
     *
     * @return the server process
     */
    public ServerProcess process() {
        return process;
    }

    /**
     * Gets the server address the client wrote in its handshake. A connection that goes through no
     * handshake (an NPC, a test connection) returns the address this process listens on,
     * {@link io.github.bearisbug.synaxis.network.socket.Server#getAddress()}.
     *
     * @return the server address used
     */
    public @Nullable String getServerAddress() {
        return process.server().getAddress();
    }


    /**
     * Gets the server port the client wrote in its handshake. A connection that goes through no
     * handshake (an NPC, a test connection) returns the port this process listens on,
     * {@link io.github.bearisbug.synaxis.network.socket.Server#getPort()}.
     *
     * @return the server port used
     */
    public int getServerPort() {
        return process.server().getPort();
    }


    /**
     * Claims the single status request a connection is allowed to make.
     *
     * @return false if a status request was already answered on this connection
     */
    @ApiStatus.Internal
    public boolean acquireStatusRequest() {
        if (statusRequested) return false;
        this.statusRequested = true;
        return true;
    }

    /**
     * Claims the single status ping a connection is allowed to make.
     *
     * @return false if a ping was already handled on this connection
     */
    @ApiStatus.Internal
    public boolean acquireStatusPing() {
        if (statusPinged) return false;
        this.statusPinged = true;
        return true;
    }

    /**
     * Kicks the player with a reason. Does nothing once {@link #disconnect()} has run: a kick that
     * arrives after it neither sends a disconnect packet nor writes a line.
     *
     * @param component the reason
     */
    public void kick(Component component) {
        if (!online) return;
        // A translatable reason is an error code of the engine, log it against the connection id
        if (component instanceof TranslatableComponent translatable)
            LOGGER.info("Kicking connection_id={} identifier={} reason={}",
                    connectionId, getIdentifier(), translatable.key());
        // Packet type depends on the current player connection state
        final ServerPacket disconnectPacket;
        if (serverState == ConnectionState.HANDSHAKE || serverState == ConnectionState.STATUS) {
            // Neither registry holds a disconnect packet, queueing one would throw on the write thread
            disconnectPacket = null;
        } else if (serverState == ConnectionState.LOGIN) {
            disconnectPacket = new LoginDisconnectPacket(component);
        } else {
            disconnectPacket = new DisconnectPacket(component);
        }
        if (disconnectPacket != null) sendPacket(disconnectPacket);
        disconnect();
    }

    /**
     * Forcing the player to disconnect.
     */
    public void disconnect() {
        final boolean wasOnline = this.online;
        this.online = false;
        if (wasOnline && !turnedAway)
            LOGGER.info("Connection closed: connection_id={} identifier={}", connectionId, getIdentifier());
        final LoginPluginMessageProcessor processor = this.loginPluginMessageProcessor;
        if (processor != null) processor.close();
        // Only the call that removes the player reports it: two threads may drop the connection at once
        final Player player = process.connection().dropPlayer(this);
        if (player != null) {
            if (serverState == ConnectionState.PLAY && !player.isRemoved()) {
                player.scheduleNextTick(Entity::remove);
                // Nothing ticks a player that has not spawned yet: the tick decides who reports it
                process.connection().leftBeforeSpawn(player);
            } else {
                process.eventHandler().call(new PlayerDisconnectEvent(player));
                EventsJFR.newPlayerLeave(player.getUuid()).commit();
            }
        }
    }

    /**
     * Closes a connection the connection gate turned away. Unlike {@link #disconnect()} it writes no
     * line: how many there are is up to the peers, the gate counts them instead.
     */
    @ApiStatus.Internal
    public void disconnectTurnedAway() {
        this.turnedAway = true;
        disconnect();
    }

    /**
     * Gets the player linked to this connection.
     *
     * @return the player, can be null if not initialized yet
     */
    public @Nullable Player getPlayer() {
        return player;
    }

    /**
     * Changes the player linked to this connection. Called by the {@link Player} constructor; plugins
     * must not call it.
     *
     * @param player the player
     */
    public void setPlayer(Player player) {
        this.player = player;
    }

    /**
     * Gets if the client is still connected to the server.
     *
     * @return true if the player is online, false otherwise
     */
    public boolean isOnline() {
        return online;
    }

    /**
     * @deprecated Use {@link #getClientState()} or {@link #getServerState()} instead.
     */
    @Deprecated(forRemoval = true)
    public ConnectionState getConnectionState() {
        return getClientState();
    }

    public ConnectionState getServerState() {
        return serverState;
    }

    public ConnectionState getClientState() {
        return clientState;
    }

    /**
     * Changes the state the client is in, which the read thread decodes the next packet in. The
     * packet listener moves it as each packet that switches the state arrives
     * ({@code PacketListenerManager#processClientPacket}); changing it anywhere else makes the
     * connection decode packets in the wrong state. Plugins must not call it.
     *
     * @param clientState the new client state
     */
    public void setClientState(ConnectionState clientState) {
        if (this.clientState == ConnectionState.HANDSHAKE)
            this.serverState = clientState;
        this.clientState = clientState;
    }

    /**
     * Changes the state the server is in, which the write thread encodes the next packet in. The
     * write thread moves it once it has written a packet that switches the state
     * ({@code PlayerSocketConnection#writePacketSync}); changing it anywhere else makes the
     * connection encode packets in the wrong state. Any state but the login one also ends the login
     * plugin requests, those still waiting for an answer fail. Plugins must not call it.
     *
     * @param serverState the new server state
     */
    public void setServerState(ConnectionState serverState) {
        this.serverState = serverState;
        if (serverState != ConnectionState.LOGIN) {
            // Login plugin requests only exist in the login state
            final LoginPluginMessageProcessor processor = this.loginPluginMessageProcessor;
            if (processor != null) processor.close();
            this.loginPluginMessageProcessor = null;
        }
    }

    public @Nullable PlayerPublicKey playerPublicKey() {
        return playerPublicKey;
    }

    /**
     * Changes the chat session key of the player. Nothing in the engine calls it; plugins must not
     * call it.
     *
     * @param playerPublicKey the new key
     */
    public void setPlayerPublicKey(PlayerPublicKey playerPublicKey) {
        this.playerPublicKey = playerPublicKey;
    }

    /**
     * Stores a cookie on the client, in the configuration or the play state.
     *
     * @param key  the cookie key
     * @param data the cookie data
     * @throws IllegalStateException if the connection is in another state, the login one for instance
     */
    public void storeCookie(String key, byte[] data) {
        Check.stateCondition(serverState != ConnectionState.CONFIGURATION && serverState != ConnectionState.PLAY,
                "storeCookie needs the configuration or play state, the connection is in {0}", serverState);
        sendPacket(new CookieStorePacket(key, data));
    }

    /**
     * Asks the client for a cookie.
     * <p>
     * The future completes with the value the client answers, or with {@code null} if the client
     * has no cookie under this key. Asking for the same key again before the answer replaces the
     * earlier request, whose future then never completes. Nor does it complete if the connection
     * goes away before the answer: the engine sets no timeout, so wait with {@code orTimeout} or a
     * timed {@code get}, never with an unbounded {@code join()}.
     *
     * @param key the cookie key
     * @return the value the client answers
     * @throws IllegalStateException if called from the {@code PlayerProvider}, in the configuration
     *                               state before the player exists
     */
    public CompletableFuture<byte @Nullable []> fetchCookie(String key) {
        if (serverState == ConnectionState.CONFIGURATION && getPlayer() == null) {
            // This is a bit of an unfortunate limitation. The player provider blocks the player read virtual
            // thread waiting for the player provider so a cookie response would never be received and the
            // process would deadlock.
            // We cannot create the player provider without blocking the read thread because the client
            // has already sent the initial settings packet, and we need the Player to process the response.
            // We could store the settings on the connection, but it does not seem worth to get around this case.
            throw new IllegalStateException("Cannot fetch cookie in PlayerProvider, use AsyncPlayerPreLoginEvent or AsyncPlayerConfigurationEvent");
        }
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        pendingCookieRequests.put(Key.key(key), future);
        sendPacket(new CookieRequestPacket(key));
        return future;
    }

    @ApiStatus.Internal
    public void receiveCookieResponse(String key, byte @Nullable [] data) {
        CompletableFuture<byte[]> future = pendingCookieRequests.remove(Key.key(key));
        if (future != null) {
            future.complete(data);
        }
    }

    /**
     * Gets the login plugin message processor, only available during the login state.
     */
    @ApiStatus.Internal
    public LoginPluginMessageProcessor loginPluginMessageProcessor() {
        return Objects.requireNonNull(this.loginPluginMessageProcessor,
                "Login plugin message processor is only available during the login state.");
    }

    @ApiStatus.Internal
    public CompletableFuture<List<SelectKnownPacksPacket.Entry>> requestKnownPacks(List<SelectKnownPacksPacket.Entry> serverPacks) {
        Check.stateCondition(knownPacksFuture != null, "Known packs already pending");
        sendPacket(new SelectKnownPacksPacket(serverPacks));
        final CompletableFuture<List<SelectKnownPacksPacket.Entry>> future = new CompletableFuture<>();
        this.knownPacksFuture = future;
        return future;
    }

    @ApiStatus.Internal
    public void receiveKnownPacksResponse(List<SelectKnownPacksPacket.Entry> clientPacks) {
        final var future = knownPacksFuture;
        if (future != null) {
            future.complete(clientPacks);
            knownPacksFuture = null;
        }
    }

    /**
     * Completes the pending known packs query without a client answer. A client that is gone can
     * never answer it, and the configuration thread waiting on it must not wait for the timeout.
     */
    @ApiStatus.Internal
    public void releaseKnownPacksWait() {
        final var future = knownPacksFuture;
        if (future != null) {
            knownPacksFuture = null;
            future.complete(List.of());
        }
    }

    /**
     * Attempts to transfer the player to another server, using the {@link TransferPacket}, in the
     * configuration or the play state. A plugin transfers with {@code Transfers#transfer} instead, which
     * applies the cooldown and reports the outcome ({@code docs/PLUGINS.md}).
     *
     * @param host the host, usually an IP or domain name.
     * @param port the port, usually 25565.
     * @throws IllegalStateException if the connection is in another state, the login one for instance
     * @throws IllegalArgumentException if the host is blank or the port is outside 1 to 65535, as the
     *                                  {@code OutgoingTransferEvent} it calls refuses them
     */
    public void transfer(String host, int port) {
        Check.stateCondition(serverState != ConnectionState.CONFIGURATION && serverState != ConnectionState.PLAY,
                "transfer needs the configuration or play state, the connection is in {0}", serverState);
        OutgoingTransferEvent event = new OutgoingTransferEvent(this.player, host, port);
        process.eventHandler().callCancellable(event, () -> this.sendPacket(new TransferPacket(event.getHost(), event.getPort())));
    }

    /**
     * Returns whether the player has indicated that they were redirected from another server.
     *
     * @return true if the client marked itself as transferred, false otherwise
     */
    public boolean wasTransferred() {
        return this.wasTransferred;
    }

    @ApiStatus.Internal
    public void markTransferred(boolean wasTransferred) {
        if (!wasTransferred && this.wasTransferred) {
            throw new IllegalStateException("Cannot mark transferred connection as non-transferred");
        }

        this.wasTransferred = wasTransferred;
    }

    @Override
    public String toString() {
        return "PlayerConnection{" +
                "serverState=" + serverState +
                ", clientState=" + clientState +
                ", identifier=" + getIdentifier() +
                '}';
    }
}
