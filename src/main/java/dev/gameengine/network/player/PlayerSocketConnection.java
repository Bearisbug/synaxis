package dev.gameengine.network.player;

import dev.gameengine.MinecraftServer;
import dev.gameengine.ServerFlag;
import dev.gameengine.adventure.MinestomAdventure;
import dev.gameengine.entity.Player;
import dev.gameengine.event.EventDispatcher;
import dev.gameengine.event.ListenerHandle;
import dev.gameengine.event.player.PlayerPacketOutEvent;
import dev.gameengine.network.ConnectionState;
import dev.gameengine.network.DisconnectReasons;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.packet.PacketParser;
import dev.gameengine.network.packet.PacketReading;
import dev.gameengine.network.packet.PacketVanilla;
import dev.gameengine.network.packet.PacketWriting;
import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.network.packet.client.common.ClientCookieResponsePacket;
import dev.gameengine.network.packet.client.common.ClientKeepAlivePacket;
import dev.gameengine.network.packet.client.common.ClientPingRequestPacket;
import dev.gameengine.network.packet.client.configuration.ClientFinishConfigurationPacket;
import dev.gameengine.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import dev.gameengine.network.packet.client.handshake.ClientHandshakePacket;
import dev.gameengine.network.packet.client.login.ClientEncryptionResponsePacket;
import dev.gameengine.network.packet.client.login.ClientLoginAcknowledgedPacket;
import dev.gameengine.network.packet.client.login.ClientLoginPluginResponsePacket;
import dev.gameengine.network.packet.client.login.ClientLoginStartPacket;
import dev.gameengine.network.packet.client.status.StatusRequestPacket;
import dev.gameengine.network.packet.server.BufferedPacket;
import dev.gameengine.network.packet.server.CachedPacket;
import dev.gameengine.network.packet.server.FramedPacket;
import dev.gameengine.network.packet.server.SendablePacket;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.network.packet.server.login.SetCompressionPacket;
import dev.gameengine.utils.collection.ConcurrentMessageQueues;
import dev.gameengine.utils.validate.Check;
import org.jctools.queues.MessagePassingQueue;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

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

    private final NetworkBuffer readBuffer = NetworkBuffer.resizableBuffer(
            ServerFlag.POOLED_BUFFER_SIZE, MinecraftServer.getRegistries());
    private final MessagePassingQueue<SendablePacket> packetQueue = ConcurrentMessageQueues.mpscUnboundedArrayQueue(1024);
    private final Thread readThread, writeThread;

    private final AtomicLong sentPacketCounter = new AtomicLong();
    // Index where compression starts, linked to `sentPacketCounter`
    // Used instead of a simple boolean so we can get proper timing for serialization
    private volatile long compressionStart = Long.MAX_VALUE;

    // Write lock as the default behavior of the writing thread is to park itself
    // Requires ServerFlag.FASTER_SOCKET_WRITES to be enabled
    private final AtomicBoolean writeSignaled = new AtomicBoolean(false);

    private final ListenerHandle<PlayerPacketOutEvent> outgoing = EventDispatcher.getHandle(PlayerPacketOutEvent.class);

    public PlayerSocketConnection(SocketChannel channel, SocketAddress remoteAddress, Thread readThread, Thread writeThread) {
        super();
        this.channel = channel;
        this.remoteAddress = remoteAddress;
        this.writeThread = writeThread;
        this.readThread = readThread;
    }

    public void read(PacketParser<ClientPacket> packetParser) throws IOException {
        NetworkBuffer readBuffer = this.readBuffer;
        readBuffer.readChannel(channel);

        if (ServerFlag.PROXY_PROTOCOL && !attemptedProxyProtocolDetection) {
            final ProxyProtocolDecoder.Result result = ProxyProtocolDecoder.parse(remoteAddress, readBuffer);
            if (result.status() == ProxyProtocolDecoder.Status.NEED_MORE) return;
            attemptedProxyProtocolDetection = true;
            if (result.status() == ProxyProtocolDecoder.Status.PRESENT) {
                this.remoteAddress = result.clientAddress();
            } else if (ServerFlag.PROXY_PROTOCOL_REQUIRED) {
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
            result = PacketReading.readPackets(
                    readBuffer,
                    packetParser,
                    startingState, PacketVanilla::nextClientState,
                    compression()
            );
        } catch (Throwable e) {
            rethrowFatal(e);
            // Errors thrown while still in the starting state are usually garbage
            // from scanners. A packet that errors after a state change within the
            // same batch is still checked against the starting state.
            if (startingState.ordinal() > ServerFlag.SUPPRESS_MALFORMED_PACKET_ERROR_LEVEL)
                MinecraftServer.getExceptionManager().handleException(e);
            // The remaining packets of the batch are lost, disconnect to avoid
            // reading from an invalid state.
            if (ServerFlag.REJECT_MALFORMED_PACKET) kick(DisconnectReasons.PROTOCOL_ERROR);
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
                            MinecraftServer.getPacketListenerManager().processClientPacket(packet, this);
                        } else {
                            // To be processed during the next player tick
                            final Player player = getPlayer();
                            assert player != null;
                            player.addPacketToQueue(packet);
                        }
                    } catch (Throwable e) {
                        rethrowFatal(e);
                        if (startingState.ordinal() > ServerFlag.SUPPRESS_MISUSED_PACKET_ERROR_LEVEL)
                            MinecraftServer.getExceptionManager().handleException(e);
                        // Packets already in the queue are unaffected.
                        if (ServerFlag.REJECT_MISUSED_PACKET) disconnect();
                    }
                }
                // Compact in case of incomplete read
                readBuffer.compact();
            }
            case PacketReading.Result.Empty<ClientPacket> _ -> {
                // Empty
            }
            case PacketReading.Result.Failure<ClientPacket> failure -> {
                // Resize for next read
                final long requiredCapacity = failure.requiredCapacity();
                assert requiredCapacity > readBuffer.capacity() :
                        "New capacity should be greater than the current one: " + requiredCapacity + " <= " + readBuffer.capacity();
                readBuffer.resize(requiredCapacity);
            }
        }
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
        final int threshold = MinecraftServer.getCompressionThreshold();
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

    // Requires ServerFlag.FASTER_SOCKET_WRITES
    private void unlockWriteThread() {
        if (!ServerFlag.FASTER_SOCKET_WRITES) return;
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
            if (ServerFlag.AUTOMATIC_COMPONENT_TRANSLATION && packet instanceof ServerPacket.ComponentHolding translatablePacket) {
                packet = translatablePacket.copyWithOperator(component ->
                        MinestomAdventure.COMPONENT_TRANSLATOR.apply(component, Objects.requireNonNullElseGet(player.getLocale(), MinestomAdventure::getDefaultLocale)));
            }
        }
        // Write packet
        final long start = buffer.writeIndex();
        final int compressionThreshold = compressed ? MinecraftServer.getCompressionThreshold() : 0;
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
                    final NetworkBuffer body = cachedPacket.body(state);
                    if (body != null) {
                        yield writeBuffer(buffer, body, 0, body.capacity());
                    } else {
                        PacketWriting.writeFramedPacket(buffer, state, cachedPacket.packet(state), compressionThreshold);
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
        if (ServerFlag.FASTER_SOCKET_WRITES) {
            assert this.writeThread == Thread.currentThread(): "writeThread should be the current thread";
            // Clear the signal before looking at the queue, otherwise a packet offered in
            // between is seen by neither the check below nor unlockWriteThread.
            this.writeSignaled.set(false);
        }
        if (packetQueue.isEmpty()) {
            if (!ServerFlag.FASTER_SOCKET_WRITES) {
                try {
                    // Can probably be improved by waking up at the end of the tick
                    // But this work well enough and without additional state.
                    Thread.sleep(1000 / ServerFlag.SERVER_TICKS_PER_SECOND / 2);
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
