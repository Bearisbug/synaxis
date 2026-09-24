package io.github.bearisbug.synaxis.network.player;

import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import io.github.bearisbug.synaxis.crypto.LoginCrypt;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.ListenerHandle;
import io.github.bearisbug.synaxis.event.player.PlayerPacketOutEvent;
import io.github.bearisbug.synaxis.network.ConnectionGate;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.TrafficCounters;
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

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
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

    /**
     * How long the write thread gets to flush the disconnect packet before the channel is closed
     * under it, the same budget {@code ConnectionManager#shutdown} gives it.
     */
    static final long CLOSE_AFTER_DISCONNECT_MILLIS = 1_000;

    private final SocketChannel channel;
    private SocketAddress remoteAddress;
    private boolean attemptedProxyProtocolDetection = false;
    private final AtomicBoolean closeArmed = new AtomicBoolean(false);

    // The login encryption (docs/DESIGN-PROD.md API-PROD-002). The challenge is only touched by the
    // read thread; the ciphers are read by the write thread once per packet, hence volatile.
    private byte @Nullable [] encryptionChallenge;
    private boolean encryptionResponseClaimed;
    private volatile @Nullable EncryptionContext encryption;

    // Data from client packets
    private @Nullable String loginUsername;
    private volatile @Nullable GameProfile gameProfile;
    private @Nullable String serverAddress;
    private int serverPort;
    private int protocolVersion;

    private final NetworkBuffer readBuffer;
    private final MessagePassingQueue<SendablePacket> packetQueue = ConcurrentMessageQueues.mpscUnboundedArrayQueue(1024);
    private final Thread readThread, writeThread;
    private volatile @Nullable Thread loginTimeoutThread;
    private final ConnectionGate.Ticket gateTicket;

    private final AtomicLong sentPacketCounter = new AtomicLong();
    // Index where compression starts, linked to `sentPacketCounter`
    // Used instead of a simple boolean so we can get proper timing for serialization
    private volatile long compressionStart = Long.MAX_VALUE;

    // Write lock as the default behavior of the writing thread is to park itself
    // Requires ServerConfig#fasterSocketWrites() to be enabled
    private final AtomicBoolean writeSignaled = new AtomicBoolean(false);

    private final ListenerHandle<PlayerPacketOutEvent> outgoing;
    private final TrafficCounters traffic;

    public PlayerSocketConnection(ServerProcess process, SocketChannel channel, SocketAddress remoteAddress, Thread readThread, Thread writeThread) {
        this(process, channel, remoteAddress, readThread, writeThread, process.connection().gate().untracked());
    }

    /**
     * @param gateTicket what the connection holds in the connection gate, given back when it
     *                   disconnects
     */
    public PlayerSocketConnection(ServerProcess process, SocketChannel channel, SocketAddress remoteAddress,
                                  Thread readThread, Thread writeThread, ConnectionGate.Ticket gateTicket) {
        super(process);
        this.gateTicket = gateTicket;
        this.readBuffer = NetworkBuffer.resizableBuffer(process.config().pooledBufferSize(), process.registries());
        this.outgoing = process.eventHandler().getHandle(PlayerPacketOutEvent.class);
        this.traffic = process.connection().traffic();
        this.channel = channel;
        this.remoteAddress = remoteAddress;
        this.writeThread = writeThread;
        this.readThread = readThread;
    }

    public void read(PacketParser<ClientPacket> packetParser) throws IOException {
        NetworkBuffer readBuffer = this.readBuffer;
        final long writeIndex = readBuffer.writeIndex();
        final int length = readBuffer.readChannel(channel);
        if (length > 0) traffic.received(length);
        // Decrypt what just arrived; what was already in the buffer when the cipher came on was
        // decrypted by #enableEncryption
        final EncryptionContext encryption = this.encryption;
        if (encryption != null && length > 0) decrypt(readBuffer, encryption.decrypt(), writeIndex, length);

        if (process().config().proxyProtocol() && !attemptedProxyProtocolDetection) {
            final ProxyProtocolDecoder.Result result = ProxyProtocolDecoder.parse(remoteAddress, readBuffer);
            if (result.status() == ProxyProtocolDecoder.Status.NEED_MORE) return;
            attemptedProxyProtocolDetection = true;
            if (result.status() == ProxyProtocolDecoder.Status.PRESENT) {
                // The header names whatever address its sender likes, only a load balancer may send one
                if (!process().connection().gate().isTrustedProxy(remoteAddress))
                    throw new IOException("PROXY protocol header from " + remoteAddress
                            + ", outside synaxis.proxy-protocol.trusted-networks");
                this.remoteAddress = result.clientAddress();
                // Every read starts at index zero, the packet reader compacts what it leaves behind.
                // Keeping the consumed header in front of the first packet would cost the buffer
                // that much room to write, down to none at all for a header that nearly fills it.
                readBuffer.compact();
            } else if (process().config().proxyProtocolRequired()) {
                throw new IOException("Missing required PROXY protocol header");
            }
        }

        // Process packets
        processPackets(readBuffer, packetParser);
    }

    /** The size of the read buffer, which the peer can make grow by sending large packets. */
    long readBufferCapacity() {
        return readBuffer.capacity();
    }

    private boolean compression() {
        return compressionStart != Long.MAX_VALUE;
    }

    private void processPackets(NetworkBuffer readBuffer, PacketParser<ClientPacket> packetParser) {
        // While the answer to an encryption request is outstanding, frames are read and handled
        // one at a time: whatever the peer sent behind its key is already ciphertext, which
        // #enableEncryption decrypts in place before the next frame is read.
        boolean oneAtATime;
        do {
            oneAtATime = awaitingEncryptionResponse();
            if (!processBatch(readBuffer, packetParser, oneAtATime)) return;
        } while (oneAtATime && isOnline() && readBuffer.readableBytes() > 0);
    }

    /**
     * @return true if at least one frame was taken off the buffer, false if it held no complete
     * frame or the batch failed
     */
    private boolean processBatch(NetworkBuffer readBuffer, PacketParser<ClientPacket> packetParser, boolean oneFrame) {
        final ConnectionState startingState = getClientState();
        final PacketReading.Result<ClientPacket> result;
        try {
            result = oneFrame
                    ? PacketReading.<ClientPacket>readPacket(readBuffer, packetParser, startingState,
                    PacketVanilla::nextClientState, compression(), this::readClientPacket)
                    : PacketReading.<ClientPacket>readPackets(
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
            return false;
        }
        switch (result) {
            case PacketReading.Result.Success<ClientPacket> success -> {
                traffic.read(success.packets().size());
                for (PacketReading.ParsedPacket<ClientPacket> parsedPacket : success.packets()) {
                    // A listener that dropped the connection ends the batch. A peer fits thousands
                    // of frames into one read buffer, and handling the rest of them after the kick
                    // gives it back the per packet work, and the per packet log line, that the
                    // disconnect is there to stop.
                    if (!isOnline()) break;
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
                            process().exception().handleException(e, connectionId());
                        // Packets already in the queue are unaffected.
                        if (process().config().rejectMisusedPacket()) disconnect();
                    }
                }
                // Compact in case of incomplete read
                readBuffer.compact();
                return true;
            }
            case PacketReading.Result.Empty<ClientPacket> _ -> {
                // Empty
            }
            case PacketReading.Result.Skipped<ClientPacket> _ -> {
                readBuffer.compact();
                return true;
            }
            case PacketReading.Result.Failure<ClientPacket> failure -> {
                readBuffer.compact(); // Drop the complete frames already read before growing
                final long requiredCapacity = failure.requiredCapacity();
                // The required capacity is the length the peer announced, not what it sent: growing
                // to it right away turns a handful of bytes into megabytes of off-heap memory. The
                // buffer only has to grow once the peer left it nowhere to write, and then only
                // enough to keep reading, so its size follows the bytes actually received.
                if (requiredCapacity > readBuffer.capacity() && readBuffer.writableBytes() == 0)
                    readBuffer.resize(Math.min(requiredCapacity, readBuffer.capacity() * 2));
            }
        }
        return false;
    }

    /**
     * Remembers the challenge of the encryption request about to be sent. Read thread only.
     *
     * @param challenge the four random bytes the client has to send back encrypted
     * @throws IllegalStateException if an encryption request was already sent
     */
    @ApiStatus.Internal
    public void expectEncryptionResponse(byte[] challenge) {
        Check.stateCondition(encryptionChallenge != null || encryptionResponseClaimed,
                "An encryption request was already sent");
        this.encryptionChallenge = challenge.clone();
    }

    private boolean awaitingEncryptionResponse() {
        return encryptionChallenge != null && !encryptionResponseClaimed;
    }

    /**
     * Claims the one encryption response a connection may send. Read thread only.
     *
     * @return the challenge of the encryption request, or null if none was sent or its response
     * was already claimed
     */
    @ApiStatus.Internal
    public byte @Nullable [] claimEncryptionResponse() {
        if (encryptionResponseClaimed) return null;
        this.encryptionResponseClaimed = true;
        return encryptionChallenge;
    }

    /**
     * Switches both directions of the connection to AES/CFB8. Called on the read thread while it
     * handles the encryption response: the bytes the peer sent behind that frame are decrypted in
     * place here, and every packet written from now on, a login disconnect included, is encrypted.
     *
     * @param sharedSecret the key the client sent, already decrypted
     * @throws IllegalStateException if encryption is already enabled
     */
    @ApiStatus.Internal
    public void enableEncryption(SecretKey sharedSecret) {
        Check.stateCondition(encryption != null, "Encryption is already enabled!");
        // Nothing may be waiting to be written: it was framed for a plaintext stream. The server
        // sends nothing between the encryption request and its answer to the key, so this holds
        // as long as the login flow does not change.
        assert packetQueue.isEmpty() && writeLeftover == null : "packets queued while enabling encryption";
        final EncryptionContext context = new EncryptionContext(
                LoginCrypt.streamCipher(Cipher.ENCRYPT_MODE, sharedSecret),
                LoginCrypt.streamCipher(Cipher.DECRYPT_MODE, sharedSecret));
        final NetworkBuffer readBuffer = this.readBuffer;
        final long unread = readBuffer.readableBytes();
        if (unread > 0) decrypt(readBuffer, context.decrypt(), readBuffer.readIndex(), unread);
        this.encryption = context;
    }

    private void decrypt(NetworkBuffer buffer, Cipher cipher, long start, long length) {
        final long began = System.nanoTime();
        buffer.cipher(cipher, start, length);
        traffic.decrypted(System.nanoTime() - began);
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
        final long start = buffer.writeIndex();
        if (!writePacketSync(buffer, sendable, compressed)) return false;
        // Encrypted in the connection's own buffer: a shared framed or cached body was copied into
        // it first, so the shared bytes are never touched
        final EncryptionContext encryption = this.encryption;
        final long length = buffer.writeIndex() - start;
        if (length == 0) return true; // cancelled by a listener
        if (encryption != null) {
            final long began = System.nanoTime();
            buffer.cipher(encryption.encrypt(), start, length);
            traffic.encrypted(System.nanoTime() - began);
        }
        traffic.sent(length);
        return true;
    }

    // A packet that did not fit is written again on a bigger buffer, or on the next flush.
    // The outgoing event and the translation belong to the queued packet rather than to the
    // attempt, so their result is kept until it leaves the queue. Only the write thread reads this.
    private record PendingWrite(SendablePacket queued, SendablePacket prepared) {
    }

    private @Nullable PendingWrite pendingWrite = null;

    private boolean writePacketSync(NetworkBuffer buffer, SendablePacket packet, boolean compressed) {
        final SendablePacket queued = packet;
        final ConnectionState state = getServerState();
        final PendingWrite pending = this.pendingWrite;
        if (pending != null && pending.queued() == queued) {
            // New attempt at a packet whose event and translation already ran
            packet = pending.prepared();
        } else {
            final Player player = getPlayer();
            if (player != null) {
                // Outgoing event
                if (outgoing.hasListener()) {
                    final ServerPacket serverPacket = SendablePacket.extractServerPacket(state, packet);
                    if (serverPacket != null) { // Events are not called for buffered packets
                        PlayerPacketOutEvent event = new PlayerPacketOutEvent(player, serverPacket);
                        outgoing.call(event);
                        if (event.isCancelled()) {
                            this.pendingWrite = null;
                            return true;
                        }
                    }
                }
                // Translation
                if (JvmFlags.AUTOMATIC_COMPONENT_TRANSLATION && packet instanceof ServerPacket.ComponentHolding translatablePacket) {
                    packet = translatablePacket.copyWithOperator(component ->
                            EngineAdventure.COMPONENT_TRANSLATOR.apply(component, Objects.requireNonNullElseGet(player.getLocale(), EngineAdventure::getDefaultLocale)));
                }
            }
        }
        // Write packet
        final long start = buffer.writeIndex();
        final int compressionThreshold = compressed ? process().compressionThreshold() : 0;
        try {
            final boolean success = switch (packet) {
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
            this.pendingWrite = success ? null : new PendingWrite(queued, packet);
            return success;
        } catch (IndexOutOfBoundsException _) {
            buffer.writeIndex(start);
            this.pendingWrite = new PendingWrite(queued, packet);
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

    /**
     * Hands over the watchdog closing this connection if it never reaches the configuration state,
     * so that it can be cancelled once the connection is gone. Sleeping the login timeout out would
     * keep this connection and its read buffer alive long after the socket died.
     *
     * @param thread the watchdog thread, cancelled by an interrupt
     */
    @ApiStatus.Internal
    public void watchLoginTimeout(Thread thread) {
        this.loginTimeoutThread = thread;
    }

    /** What this connection holds in the connection gate. */
    @ApiStatus.Internal
    public ConnectionGate.Ticket gateTicket() {
        return gateTicket;
    }

    @Override
    public void disconnect() {
        super.disconnect();
        gateTicket.release();
        LockSupport.unpark(writeThread);
        final Thread loginTimeoutThread = this.loginTimeoutThread;
        this.loginTimeoutThread = null;
        // The watchdog closing the connection itself is already done, only the other one is waiting
        if (loginTimeoutThread != null && loginTimeoutThread != Thread.currentThread())
            loginTimeoutThread.interrupt();
        closeOnceFlushed();
    }

    /**
     * The write thread closes the channel on its way out, after flushing the disconnect packet, and
     * a peer that stopped reading keeps that thread inside a blocking socket write for as long as
     * it keeps the TCP connection up: the kernel only takes the bytes once the peer reads them.
     * Closing the channel is the one thing that ends such a write, so once the flush budget is
     * spent it is done from a thread of its own, and with the channel go both socket threads, the
     * read buffer and everything queued for the peer. The write thread never needs this: a call
     * from there means it is not inside a write, and it closes the channel itself right after.
     */
    private void closeOnceFlushed() {
        if (Thread.currentThread() == writeThread) return;
        if (!closeArmed.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("Syn-Socket-Closer").start(() -> {
            try {
                writeThread.join(CLOSE_AFTER_DISCONNECT_MILLIS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            try {
                channel.close();
            } catch (IOException _) {
                // The write thread got there first, which is the usual case
            }
        });
    }

    public Thread readThread() {
        return readThread;
    }

    public Thread writeThread() {
        return writeThread;
    }

    private record EncryptionContext(Cipher encrypt, Cipher decrypt) {
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
