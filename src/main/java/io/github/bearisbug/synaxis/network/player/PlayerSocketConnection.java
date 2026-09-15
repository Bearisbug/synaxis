package io.github.bearisbug.synaxis.network.player;

import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.ListenerHandle;
import io.github.bearisbug.synaxis.event.player.PlayerPacketOutEvent;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketParser;
import io.github.bearisbug.synaxis.network.packet.PacketReading;
import io.github.bearisbug.synaxis.network.packet.PacketRegistry;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientCookieResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientKeepAlivePacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPingRequestPacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientFinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientEncryptionResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginAcknowledgedPacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginPluginResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientCreativeInventoryActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.status.StatusRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.BufferedPacket;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.FramedPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.SetCompressionPacket;
import io.github.bearisbug.synaxis.utils.collection.ConcurrentMessageQueues;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jctools.queues.MessagePassingQueue;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Represents a socket connection.
 * <p>
 * It is the implementation used for all network client.
 */
@ApiStatus.Internal
public class PlayerSocketConnection extends PlayerConnection {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerSocketConnection.class);
    private static final Set<Class<? extends ClientPacket>> IMMEDIATE_PROCESS_PACKETS = Set.of(
            ClientHandshakePacket.class, // First received packet
            ClientCookieResponsePacket.class,
            StatusRequestPacket.class,
            ClientPingRequestPacket.class,
            ClientKeepAlivePacket.class, // Used to calculate latency
            ClientLoginStartPacket.class,
            ClientEncryptionResponsePacket.class, // Auth request
            ClientLoginPluginResponsePacket.class,
            ClientSelectKnownPacksPacket.class, // Immediate answer to server request on config
            ClientLoginAcknowledgedPacket.class, // Handle config state
            ClientFinishConfigurationPacket.class // Enter play state
    );

    private final SocketChannel channel;
    private SocketAddress remoteAddress;
    private boolean attemptedProxyProtocolDetection = false;

    // Data from client packets
    private @Nullable String loginUsername;
    private volatile @Nullable GameProfile gameProfile;
    private @Nullable String serverAddress;
    private int serverPort;
    private int protocolVersion;

    private final NetworkBuffer readBuffer;
    private final MessagePassingQueue<SendablePacket> packetQueue = ConcurrentMessageQueues.mpscUnboundedArrayQueue(1024);
    private final Thread readThread, writeThread;

    private final AtomicLong sentPacketCounter = new AtomicLong();
    // Index where compression starts, linked to `sentPacketCounter`
    // Used instead of a simple boolean so we can get proper timing for serialization
    private volatile long compressionStart = Long.MAX_VALUE;

    // Write lock as the default behavior of the writing thread is to park itself
    // Requires ServerConfig#fasterSocketWrites() to be enabled
    private final AtomicBoolean writeSignaled = new AtomicBoolean(false);

    private final ListenerHandle<PlayerPacketOutEvent> outgoing;

    public PlayerSocketConnection(ServerProcess process, SocketChannel channel, SocketAddress remoteAddress, Thread readThread, Thread writeThread) {
        super(process);
        this.readBuffer = NetworkBuffer.resizableBuffer(process.config().pooledBufferSize(), process.registries());
        this.outgoing = process.eventHandler().getHandle(PlayerPacketOutEvent.class);
        this.channel = channel;
        this.remoteAddress = remoteAddress;
        this.writeThread = writeThread;
        this.readThread = readThread;
    }

    public void read(PacketParser<ClientPacket> packetParser) throws IOException {
        NetworkBuffer readBuffer = this.readBuffer;
        readBuffer.readChannel(channel);

        if (process().config().proxyProtocol() && !attemptedProxyProtocolDetection) {
            final ProxyProtocolDecoder.Result result = ProxyProtocolDecoder.parse(remoteAddress, readBuffer);
            if (result.status() == ProxyProtocolDecoder.Status.NEED_MORE) return;
            attemptedProxyProtocolDetection = true;
            if (result.status() == ProxyProtocolDecoder.Status.PRESENT) {
                this.remoteAddress = result.clientAddress();
            } else if (process().config().proxyProtocolRequired()) {
                throw new IOException("Missing required PROXY protocol header");
            }
        }

        // Process packets
        processPackets(readBuffer, packetParser);
    }

    private boolean compression() {
        return compressionStart != Long.MAX_VALUE;
    }

    private void processPackets(NetworkBuffer readBuffer, PacketParser<ClientPacket> packetParser) {
        final ConnectionState startingState = getClientState();
        final PacketReading.Result<ClientPacket> result;
        try {
            result = PacketReading.<ClientPacket>readPackets(
                    readBuffer,
                    packetParser,
                    startingState, PacketVanilla::nextClientState,
                    compression(),
                    this::readClientPacket
            );
        } catch (Throwable e) {
            rethrowFatal(e);
            // Errors thrown while still in the starting state are usually garbage
            // from scanners. A packet that errors after a state change within the
            // same batch is still checked against the starting state.
            if (startingState.ordinal() > process().config().suppressMalformedPacketErrorLevel())
                logProtocolError(e);
            // The remaining packets of the batch are lost, disconnect to avoid
            // reading from an invalid state.
            if (process().config().rejectMalformedPacket()) kick(DisconnectReasons.PROTOCOL_ERROR);
            return;
        }
        switch (result) {
            case PacketReading.Result.Success<ClientPacket> success -> {
                for (PacketReading.ParsedPacket<ClientPacket> parsedPacket : success.packets()) {
                    final ClientPacket packet = parsedPacket.packet();

                    try {
                        final boolean processImmediately = IMMEDIATE_PROCESS_PACKETS.contains(packet.getClass());
                        if (processImmediately) {
                            // Interpret the packet using the connection state we received it.
                            process().packetListener().processClientPacket(packet, this);
                        } else {
                            // To be processed during the next player tick
                            final Player player = getPlayer();
                            assert player != null;
                            player.addPacketToQueue(packet);
                        }
                    } catch (Throwable e) {
                        rethrowFatal(e);
                        if (startingState.ordinal() > process().config().suppressMisusedPacketErrorLevel())
                            process().exception().handleException(e);
                        // Packets already in the queue are unaffected.
                        if (process().config().rejectMisusedPacket()) disconnect();
                    }
                }
                // Compact in case of incomplete read
                readBuffer.compact();
            }
            case PacketReading.Result.Empty<ClientPacket> _ -> {
                // Empty
            }
            case PacketReading.Result.Skipped<ClientPacket> _ -> readBuffer.compact();
            case PacketReading.Result.Failure<ClientPacket> failure -> {
                readBuffer.compact(); // Drop the complete frames already read before growing
                final long requiredCapacity = failure.requiredCapacity();
                if (requiredCapacity > readBuffer.capacity()) readBuffer.resize(requiredCapacity);
            }
        }
    }

    /**
     * Decides whether a framed payload is worth decoding.
     *
     * <p>A creative set slot packet carries a full item, components included, which is by far the
     * most expensive thing a client can ask the server to decode. Only a player in creative mode is
     * allowed to send one, and that check does not need the decoded packet, so it happens first.</p>
     *
     * @return the decoded packet, or {@code null} to skip the rest of the frame
     */
    @Nullable ClientPacket readClientPacket(PacketRegistry.PacketInfo<? extends ClientPacket> packetInfo,
                                            NetworkBuffer buffer) {
        if (packetInfo.packetClass() == ClientCreativeInventoryActionPacket.class) {
            final Player player = getPlayer();
            if (player == null || player.getGameMode() != GameMode.CREATIVE) return null;
        }
        return packetInfo.serializer().read(buffer);
    }

    /**
     * Reports a frame this connection got wrong: a zero length frame, a payload nested too deeply, a
     * binary tag or a collection over its limit.
     *
     * <p>All of these are peer driven and can be produced as fast as a socket can be written, so
     * they are one WARN line carrying the connection id and the reason. The stack trace only names
     * the point in the decoder where the input stopped making sense, which is worth having while
     * debugging and not worth thousands of lines in a production log, so it goes to DEBUG.</p>
     */
    private void logProtocolError(Throwable error) {
        LOGGER.warn("Protocol error: connection_id={} identifier={} state={} reason={}",
                connectionId(), getIdentifier(), getClientState(), describeCause(error));
        LOGGER.debug("Protocol error stack trace: connection_id={}", connectionId(), error);
    }

    /** Renders the innermost cause of {@code error} as a single {@code Type: message} line. */
    private static String describeCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        final String message = cause.getMessage();
        return message != null ? cause.getClass().getSimpleName() + ": " + message
                : cause.getClass().getSimpleName();
    }

    /**
     * Catching {@link Throwable} keeps a malformed packet from killing the read thread, but an
     * exhausted heap is not a per-connection problem and must not become a quiet disconnect.
     */
    private static void rethrowFatal(Throwable throwable) {
        if (throwable instanceof OutOfMemoryError error) throw error;
    }

    /**
     * Enables compression and add a new codec to the pipeline.
     *
     * @throws IllegalStateException if encryption is already enabled for this connection
     */
    public void startCompression() {
        Check.stateCondition(compression(), "Compression is already enabled!");
        this.compressionStart = sentPacketCounter.get();
        final int threshold = process().compressionThreshold();
        Check.stateCondition(threshold == 0, "Compression cannot be enabled because the threshold is equal to 0");
        sendPacket(new SetCompressionPacket(threshold));
    }

    @Override
    public void sendPacket(SendablePacket packet) {
        this.packetQueue.relaxedOffer(packet);
        unlockWriteThread();
    }

    @Override
    public void sendPackets(Collection<? extends SendablePacket> packets) {
        for (SendablePacket packet : packets) this.packetQueue.relaxedOffer(packet);
        unlockWriteThread();
    }

    // Requires ServerConfig#fasterSocketWrites()
    private void unlockWriteThread() {
        if (!process().config().fasterSocketWrites()) return;
        if (!this.writeSignaled.compareAndExchange(false, true)) {
            LockSupport.unpark(writeThread);
        }
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * Changes the internal remote address field.
     * <p>
     * Mostly unsafe, used internally when interacting with a proxy.
     *
     * @param remoteAddress the new connection remote address
     */
    @ApiStatus.Internal
    public void setRemoteAddress(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public @Nullable GameProfile gameProfile() {
        return gameProfile;
    }

    public void UNSAFE_setProfile(GameProfile gameProfile) {
        this.gameProfile = gameProfile;
    }

    /**
     * Retrieves the username received from the client during connection.
     * <p>
     * This value has not been checked and could be anything.
     *
     * @return the username given by the client, unchecked
     */
    public @Nullable String getLoginUsername() {
        return loginUsername;
    }

    /**
     * Sets the internal login username field.
     *
     * @param loginUsername the new login username field
     */
    public void UNSAFE_setLoginUsername(String loginUsername) {
        this.loginUsername = loginUsername;
    }

    /**
     * Gets the server address that the client used to connect.
     * <p>
     * WARNING: it is given by the client, it is possible for it to be wrong.
     *
     * @return the server address used
     */
    @Override
    public @Nullable String getServerAddress() {
        return serverAddress;
    }

    /**
     * Gets the server port that the client used to connect.
     * <p>
     * WARNING: it is given by the client, it is possible for it to be wrong.
     *
     * @return the server port used
     */
    @Override
    public int getServerPort() {
        return serverPort;
    }

    /**
     * Gets the protocol version of a client.
     *
     * @return protocol version of client.
     */
    @Override
    public int getProtocolVersion() {
        return protocolVersion;
    }

    /**
     * Used in {@link ClientHandshakePacket} to change the internal fields.
     *
     * @param serverAddress   the server address which the client used
     * @param serverPort      the server port which the client used
     * @param protocolVersion the protocol version which the client used
     */
    public void refreshServerInformation(@Nullable String serverAddress, int serverPort, int protocolVersion) {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.protocolVersion = protocolVersion;
    }

    private boolean writeSendable(NetworkBuffer buffer, SendablePacket sendable, boolean compressed) {
        return writePacketSync(buffer, sendable, compressed);
    }

    private boolean writePacketSync(NetworkBuffer buffer, SendablePacket packet, boolean compressed) {
        final Player player = getPlayer();
        final ConnectionState state = getServerState();
        if (player != null) {
            // Outgoing event
            if (outgoing.hasListener()) {
                final ServerPacket serverPacket = SendablePacket.extractServerPacket(state, packet);
                if (serverPacket != null) { // Events are not called for buffered packets
                    PlayerPacketOutEvent event = new PlayerPacketOutEvent(player, serverPacket);
                    outgoing.call(event);
                    if (event.isCancelled()) return true;
                }
            }
            // Translation
            if (JvmFlags.AUTOMATIC_COMPONENT_TRANSLATION && packet instanceof ServerPacket.ComponentHolding translatablePacket) {
                packet = translatablePacket.copyWithOperator(component ->
                        EngineAdventure.COMPONENT_TRANSLATOR.apply(component, Objects.requireNonNullElseGet(player.getLocale(), EngineAdventure::getDefaultLocale)));
            }
        }
        // Write packet
        final long start = buffer.writeIndex();
        final int compressionThreshold = compressed ? process().compressionThreshold() : 0;
        try {
            return switch (packet) {
                case ServerPacket serverPacket -> {
                    var nextState = PacketVanilla.nextServerState(serverPacket, state);
                    PacketWriting.writeFramedPacket(buffer, state, serverPacket, compressionThreshold);
                    // Only after the packet is written, a rejected packet is retried next flush
                    // and would then be looked up in the registry of the new state.
                    if (nextState != state) setServerState(nextState);
                    yield true;
                }
                case FramedPacket framedPacket -> {
                    final NetworkBuffer body = framedPacket.body();
                    yield writeBuffer(buffer, body, 0, body.capacity());
                }
                case CachedPacket cachedPacket -> {
                    final NetworkBuffer body = cachedPacket.body(state, process().registries(), compressionThreshold);
                    if (body != null) {
                        yield writeBuffer(buffer, body, 0, body.capacity());
                    } else {
                        PacketWriting.writeFramedPacket(buffer, state, cachedPacket.packet(), compressionThreshold);
                        yield true;
                    }
                }
                case BufferedPacket bufferedPacket -> {
                    final NetworkBuffer rawBuffer = bufferedPacket.buffer();
                    final long index = bufferedPacket.index();
                    final long length = bufferedPacket.length();
                    yield writeBuffer(buffer, rawBuffer, index, length);
                }
            };
        } catch (IndexOutOfBoundsException _) {
            buffer.writeIndex(start);
            return false;
        }
    }

    private static boolean writeBuffer(NetworkBuffer buffer, NetworkBuffer body, long index, long length) {
        if (buffer.writableBytes() < length) {
            // Not enough space in the buffer
            return false;
        }
        NetworkBuffer.copy(body, index, buffer, buffer.writeIndex(), length);
        buffer.advanceWrite(length);
        return true;
    }

    private @Nullable NetworkBuffer writeLeftover = null;

    public void flushSync() throws IOException {
        // Write leftover if any
        NetworkBuffer leftover = this.writeLeftover;
        if (leftover != null) {
            final boolean success = leftover.writeChannel(channel);
            if (success) {
                this.writeLeftover = null;
                PacketVanilla.PACKET_POOL.add(leftover);
            } else {
                // Failed to write the whole leftover, try again next flush
                return;
            }
        }
        // Consume queued packets
        var packetQueue = this.packetQueue;
        if (process().config().fasterSocketWrites()) {
            assert this.writeThread == Thread.currentThread(): "writeThread should be the current thread";
            // Clear the signal before looking at the queue, otherwise a packet offered in
            // between is seen by neither the check below nor unlockWriteThread.
            this.writeSignaled.set(false);
        }
        if (packetQueue.isEmpty()) {
            if (!process().config().fasterSocketWrites()) {
                try {
                    // Can probably be improved by waking up at the end of the tick
                    // But this work well enough and without additional state.
                    Thread.sleep(1000 / JvmFlags.SERVER_TICKS_PER_SECOND / 2);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            } else {
                if (!isOnline()) return; // already offline, don't park
                LockSupport.park(this);
                if (packetQueue.isEmpty()) return; // woken by disconnect signal, not by packets
            }
        }
        if (!channel.isConnected()) throw new EOFException("Channel is closed");
        NetworkBuffer buffer = PacketVanilla.PACKET_POOL.get();
        buffer.registries(process().registries());
        // Write to buffer
        PacketWriting.writeQueue(buffer, packetQueue, 1, (b, packet) -> {
            final boolean compressed = sentPacketCounter.get() > compressionStart;
            final boolean success = writeSendable(b, packet, compressed);
            if (success) sentPacketCounter.getAndIncrement();
            return success;
        });
        // Write to channel
        final boolean success = buffer.writeChannel(channel);
        // Keep the buffer if not fully written
        if (success) PacketVanilla.PACKET_POOL.add(buffer);
        else this.writeLeftover = buffer;
    }

    @Override
    public void disconnect() {
        super.disconnect();
        LockSupport.unpark(writeThread);
    }

    public Thread readThread() {
        return readThread;
    }

    public Thread writeThread() {
        return writeThread;
    }

    @ApiStatus.Internal
    public void cleanup() {
        final var writeLeftover = this.writeLeftover;
        if (writeLeftover != null) {
            PacketVanilla.PACKET_POOL.add(writeLeftover);
            this.writeLeftover = null;
        }
    }
}
