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
    private boolean statusRequested;
    volatile boolean online;
    private volatile boolean wasTransferred;

    @SuppressWarnings("this-escape") // deliberate self registration during construction
    private @Nullable LoginPluginMessageProcessor loginPluginMessageProcessor = new LoginPluginMessageProcessor(this);

    private @Nullable CompletableFuture<List<SelectKnownPacksPacket.Entry>> knownPacksFuture = null; // Present only when waiting for a response from the client.

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
        return online
                && serverState.ordinal() < ConnectionState.CONFIGURATION.ordinal()
                && nowMillis - connectedAtMillis > process.config().loginTimeout();
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
     * Gets the server address that the client used to connect.
     * <p>
     * WARNING: it is given by the client, it is possible for it to be wrong.
     *
     * @return the server address used
     */
    public @Nullable String getServerAddress() {
        return process.server().getAddress();
    }


    /**
     * Gets the server port that the client used to connect.
     * <p>
     * WARNING: it is given by the client, it is possible for it to be wrong.
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
     * Kicks the player with a reason.
     *
     * @param component the reason
     */
    public void kick(Component component) {
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
        if (wasOnline)
            LOGGER.info("Connection closed: connection_id={} identifier={}", connectionId, getIdentifier());
        final Player player = process.connection().getPlayer(this);
        if (player != null) {
            process.connection().removePlayer(this);
            if (serverState == ConnectionState.PLAY && !player.isRemoved())
                player.scheduleNextTick(Entity::remove);
            else {
                process.eventHandler().call(new PlayerDisconnectEvent(player));
                EventsJFR.newPlayerLeave(player.getUuid()).commit();
            }
        }
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
     * Changes the player linked to this connection.
     * <p>
     * WARNING: unsafe.
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

    public void setClientState(ConnectionState clientState) {
        if (this.clientState == ConnectionState.HANDSHAKE)
            this.serverState = clientState;
        this.clientState = clientState;
    }

    public void setServerState(ConnectionState serverState) {
        this.serverState = serverState;
        if (serverState != ConnectionState.LOGIN) {
            // Clear the plugin request map (it is not used beyond login)
            this.loginPluginMessageProcessor = null;
        }
    }

    public @Nullable PlayerPublicKey playerPublicKey() {
        return playerPublicKey;
    }

    public void setPlayerPublicKey(PlayerPublicKey playerPublicKey) {
        this.playerPublicKey = playerPublicKey;
    }

    public void storeCookie(String key, byte[] data) {
        sendPacket(new CookieStorePacket(key, data));
    }

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
     * Attempts to transfer the player to another server, using the {@link TransferPacket}.
     *
     * @param host the host, usually an IP or domain name.
     * @param port the port, usually 25565.
     */
    public void transfer(String host, int port) {
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
