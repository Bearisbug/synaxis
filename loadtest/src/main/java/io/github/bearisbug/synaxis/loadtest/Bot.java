package io.github.bearisbug.synaxis.loadtest;

import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.crypto.KeyUtils;
import io.github.bearisbug.synaxis.crypto.LoginCrypt;
import io.github.bearisbug.synaxis.game.protocol.C2SHello;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.ProtocolSchema;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientCookieResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientKeepAlivePacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPongPacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientAcceptCodeOfConductPacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientFinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientEncryptionResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginAcknowledgedPacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginPluginResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChunkBatchReceivedPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientConfigurationAckPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerLoadedPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportConfirmPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTickEndPacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.CookieRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.KeepAlivePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PingPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.CodeOfConductPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.FinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.SelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.EncryptionRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginDisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginPluginRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.SetCompressionPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkBatchFinishedPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkBatchStartPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerPositionAndLookPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.StartConfigurationPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * One bot ({@code docs/DESIGN-PROD.md} {@code API-PROD-025}): a real login (handshake, login start,
 * the encryption exchange when the server asks for it, configuration), then play, where it answers
 * what a vanilla client answers — keep-alives, pings, teleports and every {@code ChunkBatchFinished}
 * with a {@code ChunkBatchReceived} — and moves along its {@link Profile} with a position packet and
 * a tick end every tick.
 * <p>
 * One virtual thread reads, one moves. A frame is only inflated as far as its packet id unless the
 * bot has to answer it, so a bot costs little more than the bytes it receives.
 */
final class Bot {

    /** What every bot of a run shares. */
    record Settings(String host, int port, Profile profile, boolean encryption, float chunksPerTick, boolean hello,
                    double warmupSeconds, int timeoutMillis) {
    }

    private static final String HANDSHAKE_CHANNEL = "synaxis:handshake";
    private static final int MOD_VERSION = 1;
    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private static final int KEEP_ALIVE = Protocol.serverId(ConnectionState.PLAY, KeepAlivePacket.class);
    private static final int PING = Protocol.serverId(ConnectionState.PLAY, PingPacket.class);
    private static final int TELEPORT = Protocol.serverId(ConnectionState.PLAY, PlayerPositionAndLookPacket.class);
    private static final int BATCH_START = Protocol.serverId(ConnectionState.PLAY, ChunkBatchStartPacket.class);
    private static final int BATCH_FINISHED = Protocol.serverId(ConnectionState.PLAY, ChunkBatchFinishedPacket.class);
    private static final int CHUNK_DATA = Protocol.serverId(ConnectionState.PLAY, ChunkDataPacket.class);
    private static final int DISCONNECT = Protocol.serverId(ConnectionState.PLAY, DisconnectPacket.class);
    private static final int START_CONFIGURATION = Protocol.serverId(ConnectionState.PLAY, StartConfigurationPacket.class);
    private static final Set<Integer> ANSWERED = Set.of(KEEP_ALIVE, PING, TELEPORT, BATCH_FINISHED, DISCONNECT);

    final int index;
    final String username;
    private final Settings settings;
    private final BotStats stats;
    private final Socket socket = new Socket();
    private final Object writeLock = new Object();
    private final Deflater deflater = new Deflater();
    private final Inflater inflater = new Inflater();
    private final byte[] head = new byte[5];

    private InputStream in;
    private OutputStream out;
    private int compressionThreshold = -1;
    private ConnectionState state = ConnectionState.LOGIN;

    /** Packets this bot sent, read once a second by the run for the rate windows. */
    final AtomicLong sent = new AtomicLong();
    // Only touched by the run's sampler
    final long[] window = new long[7];
    int windowFilled = -1;
    long lastSent;

    private volatile long playSince;
    private volatile long closedAt;
    private volatile boolean closed;
    private long chunks;
    private long batchStarted;
    private double spawnX, spawnY, spawnZ;
    private volatile boolean moving;

    Bot(int index, Settings settings, BotStats stats) {
        this.index = index;
        this.username = String.format(Locale.ROOT, "bot%05d", index);
        this.settings = settings;
        this.stats = stats;
    }

    /**
     * Logs in, then plays until {@code endNanos} or until the server lets go. Blocks; runs on the
     * bot's own thread.
     *
     * @param endNanos when the run ends, {@link System#nanoTime()}
     */
    void run(long endNanos) {
        final long connectStart = System.nanoTime();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(settings.host(), settings.port()), settings.timeoutMillis());
            socket.setSoTimeout(settings.timeoutMillis());
            in = new BufferedInputStream(socket.getInputStream(), 1 << 16);
            out = socket.getOutputStream();
            login(connectStart);
            configure();
        } catch (IOException | RuntimeException e) {
            BotStats.count(stats.loginFailures, reason(e));
            close();
            return;
        }
        stats.loggedIn.increment();
        playSince = System.nanoTime();
        try {
            socket.setSoTimeout(1_000);
            play(endNanos);
        } catch (IOException | RuntimeException e) {
            if (!closed && System.nanoTime() < endNanos) {
                stats.lost.increment();
                BotStats.count(stats.kicks, reason(e));
            }
        } finally {
            close();
            account();
        }
    }

    private static String reason(Exception e) {
        if (e instanceof Kicked kicked) return kicked.getMessage();
        return "closed: " + e.getClass().getSimpleName();
    }

    // -- login and configuration ------------------------------------------------------------

    private void login(long connectStart) throws IOException {
        send(ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION, settings.host(),
                settings.port(), ClientHandshakePacket.Intent.LOGIN));
        send(ConnectionState.LOGIN, new ClientLoginStartPacket(username, GameProfile.offlineUuid(username)));
        while (true) {
            final ServerPacket packet = readFully(ConnectionState.LOGIN);
            switch (packet) {
                case SetCompressionPacket compression -> compressionThreshold = compression.threshold();
                case EncryptionRequestPacket request -> encrypt(request);
                case LoginPluginRequestPacket request -> send(ConnectionState.LOGIN, new ClientLoginPluginResponsePacket(
                        request.messageId(), settings.hello() && request.channel().equals(HANDSHAKE_CHANNEL) ? hello() : null));
                case CookieRequestPacket cookie -> send(ConnectionState.LOGIN, new ClientCookieResponsePacket(cookie.key(), null));
                case LoginSuccessPacket _ -> {
                    stats.loginMillis.record((System.nanoTime() - connectStart) / 1e6);
                    send(ConnectionState.LOGIN, new ClientLoginAcknowledgedPacket());
                    state = ConnectionState.CONFIGURATION;
                    return;
                }
                case LoginDisconnectPacket refused -> throw new Kicked("login refused: " + key(refused.kickMessage()));
                default -> {
                    // nothing else is answered during login
                }
            }
        }
    }

    private static byte[] hello() {
        final MsgBuf buf = MsgBuf.writing();
        new C2SHello(MOD_VERSION, ProtocolSchema.SCHEMA_VERSION).write(buf);
        return buf.toByteArray();
    }

    /** Answers the request the way a vanilla client does and switches both directions to AES/CFB8. */
    private void encrypt(EncryptionRequestPacket request) throws IOException {
        if (!settings.encryption())
            throw new IOException("the server asked for encryption: run with --encryption on");
        final byte[] secret = new byte[LoginCrypt.SHARED_SECRET_LENGTH];
        new SecureRandom().nextBytes(secret);
        final PublicKey publicKey = KeyUtils.publicRSAKeyFrom(request.publicKey());
        send(ConnectionState.LOGIN, new ClientEncryptionResponsePacket(rsa(publicKey, secret),
                rsa(publicKey, request.verifyToken())));
        final SecretKey key = LoginCrypt.sharedSecret(secret);
        synchronized (writeLock) {
            // The server sends nothing between its request and our key, so nothing plaintext is buffered
            in = new BufferedInputStream(new CipherInputStream(socket.getInputStream(),
                    LoginCrypt.streamCipher(Cipher.DECRYPT_MODE, key)), 1 << 16);
            out = new CipherOutputStream(socket.getOutputStream(), LoginCrypt.streamCipher(Cipher.ENCRYPT_MODE, key));
        }
    }

    private static byte[] rsa(PublicKey key, byte[] data) throws IOException {
        try {
            final Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IOException("cannot answer the encryption request", e);
        }
    }

    private void configure() throws IOException {
        if (settings.hello())
            send(ConnectionState.CONFIGURATION, new ClientPluginMessagePacket(HANDSHAKE_CHANNEL, hello()));
        while (true) {
            final ServerPacket packet = readFully(ConnectionState.CONFIGURATION);
            switch (packet) {
                case SelectKnownPacksPacket known ->
                        send(ConnectionState.CONFIGURATION, new ClientSelectKnownPacksPacket(known.entries()));
                case KeepAlivePacket keepAlive ->
                        send(ConnectionState.CONFIGURATION, new ClientKeepAlivePacket(keepAlive.id()));
                case PingPacket ping -> send(ConnectionState.CONFIGURATION, new ClientPongPacket(ping.id()));
                case CookieRequestPacket cookie ->
                        send(ConnectionState.CONFIGURATION, new ClientCookieResponsePacket(cookie.key(), null));
                case CodeOfConductPacket _ -> send(ConnectionState.CONFIGURATION, new ClientAcceptCodeOfConductPacket());
                case FinishConfigurationPacket _ -> {
                    send(ConnectionState.CONFIGURATION, new ClientFinishConfigurationPacket());
                    state = ConnectionState.PLAY;
                    return;
                }
                case DisconnectPacket kicked -> throw new Kicked("configuration: " + key(kicked.message()));
                default -> {
                    // registries, tags and the rest of the configuration stream need no answer
                }
            }
        }
    }

    // -- play -------------------------------------------------------------------------------

    private void play(long endNanos) throws IOException {
        while (!closed && System.nanoTime() < endNanos) {
            final Frame frame;
            try {
                frame = readFrame();
            } catch (SocketTimeoutException timeout) {
                if (midFrame) throw timeout;
                continue;
            }
            if (state == ConnectionState.CONFIGURATION) {
                // The server took the player back to configuration; answer it as a client does
                stats.received(state, frame.id, frame.wireBytes);
                reconfigure(frame);
                continue;
            }
            stats.received(ConnectionState.PLAY, frame.id, frame.wireBytes);
            final int id = frame.id;
            if (id == CHUNK_DATA) {
                chunk(frame.wireBytes);
            } else if (id == BATCH_START) {
                batchStarted = System.nanoTime();
            } else if (id == START_CONFIGURATION) {
                send(ConnectionState.PLAY, new ClientConfigurationAckPacket());
                state = ConnectionState.CONFIGURATION;
            } else if (ANSWERED.contains(id)) {
                answer(Protocol.decode(ConnectionState.PLAY, id, frame.body(), frame.payloadOffset()));
            }
        }
    }

    private void reconfigure(Frame frame) throws IOException {
        final ServerPacket packet = Protocol.decode(ConnectionState.CONFIGURATION, frame.id, frame.body(), frame.payloadOffset());
        switch (packet) {
            case KeepAlivePacket keepAlive -> send(ConnectionState.CONFIGURATION, new ClientKeepAlivePacket(keepAlive.id()));
            case SelectKnownPacksPacket known ->
                    send(ConnectionState.CONFIGURATION, new ClientSelectKnownPacksPacket(known.entries()));
            case FinishConfigurationPacket _ -> {
                send(ConnectionState.CONFIGURATION, new ClientFinishConfigurationPacket());
                state = ConnectionState.PLAY;
            }
            case DisconnectPacket kicked -> throw new Kicked(key(kicked.message()));
            case null, default -> {
                // nothing to answer
            }
        }
    }

    private void answer(@Nullable ServerPacket packet) throws IOException {
        switch (packet) {
            case KeepAlivePacket keepAlive -> send(ConnectionState.PLAY, new ClientKeepAlivePacket(keepAlive.id()));
            case PingPacket ping -> send(ConnectionState.PLAY, new ClientPongPacket(ping.id()));
            case PlayerPositionAndLookPacket teleport -> {
                send(ConnectionState.PLAY, new ClientTeleportConfirmPacket(teleport.teleportId()));
                if (!moving) {
                    spawnX = teleport.position().x();
                    spawnY = teleport.position().y();
                    spawnZ = teleport.position().z();
                    send(ConnectionState.PLAY, new ClientPlayerLoadedPacket());
                    moving = true;
                    Thread.ofVirtual().name("bot-move-" + index).start(this::move);
                }
            }
            case ChunkBatchFinishedPacket _ -> {
                if (batchStarted != 0) stats.chunkBatchMillis.record((System.nanoTime() - batchStarted) / 1e6);
                stats.chunkBatches.increment();
                send(ConnectionState.PLAY, new ClientChunkBatchReceivedPacket(settings.chunksPerTick()));
            }
            case DisconnectPacket kicked -> throw new Kicked(key(kicked.message()));
            case null, default -> {
                // decoded but nothing to answer
            }
        }
    }

    private void chunk(int wireBytes) {
        chunks++;
        stats.chunkPackets.increment();
        stats.chunkBytes.add(wireBytes);
        if ((System.nanoTime() - playSince) / 1e9 >= settings.warmupSeconds()) {
            stats.steadyChunkPackets.increment();
            stats.steadyChunkBytes.add(wireBytes);
        }
    }

    /** Moves every tick along the profile: a position packet and a tick end, like a walking client. */
    private void move() {
        final long start = System.nanoTime();
        long tick = 0;
        try {
            while (!closed) {
                final long deadline = start + ++tick * TICK_NANOS;
                final long wait = deadline - System.nanoTime();
                if (wait > 0) LockSupport.parkNanos(wait);
                if (closed) return;
                if (state != ConnectionState.PLAY) continue;
                final double[] at = settings.profile().position(index, spawnX, spawnY, spawnZ,
                        (System.nanoTime() - start) / 1e9);
                final boolean onGround = settings.profile().lift == 0;
                send(ConnectionState.PLAY, new ClientPlayerPositionPacket(new Vec(at[0], at[1], at[2]),
                        (byte) (onGround ? ClientPlayerPositionPacket.FLAG_ON_GROUND : 0)));
                send(ConnectionState.PLAY, new ClientTickEndPacket());
            }
        } catch (IOException | RuntimeException _) {
            // The read loop sees the same broken socket and reports it
            close();
        }
    }

    private void account() {
        final long since = playSince;
        if (since == 0) return;
        final double seconds = (closedAt - since) / 1e9;
        stats.connectionSeconds.add(seconds);
        stats.steadyConnectionSeconds.add(Math.max(0, seconds - settings.warmupSeconds()));
        stats.chunksPerBot.record((double) chunks);
    }

    boolean inPlay() {
        return playSince != 0 && !closed;
    }

    long playSince() {
        return playSince;
    }

    void close() {
        synchronized (writeLock) {
            if (closed) return;
            closed = true;
            closedAt = System.nanoTime();
        }
        try {
            socket.close();
        } catch (IOException _) {
            // closing a broken socket is the normal end here
        }
    }

    // -- framing ----------------------------------------------------------------------------

    private void send(ConnectionState sendState, ClientPacket packet) throws IOException {
        final byte[] body = Protocol.encode(sendState, packet);
        final ByteArrayOutputStream frame = new ByteArrayOutputStream(body.length + 8);
        synchronized (writeLock) {
            if (compressionThreshold < 0) {
                writeVarInt(frame, body.length);
                frame.writeBytes(body);
            } else {
                final ByteArrayOutputStream data = new ByteArrayOutputStream(body.length + 4);
                if (body.length >= compressionThreshold) {
                    writeVarInt(data, body.length);
                    data.writeBytes(deflate(body));
                } else {
                    writeVarInt(data, 0);
                    data.writeBytes(body);
                }
                writeVarInt(frame, data.size());
                data.writeTo(frame);
            }
            if (closed) throw new EOFException("closed");
            out.write(frame.toByteArray());
            out.flush();
        }
        sent.incrementAndGet();
        stats.sent(packet.getClass().getSimpleName());
    }

    private byte[] deflate(byte[] body) {
        deflater.reset();
        deflater.setInput(body);
        deflater.finish();
        final ByteArrayOutputStream out = new ByteArrayOutputStream(body.length);
        final byte[] chunk = new byte[1024];
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk));
        return out.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }

    private boolean midFrame;

    private int readVarInt() throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            final int part = in.read();
            if (part < 0) throw new EOFException("the server closed the connection");
            midFrame = true;
            value |= (part & 0x7F) << shift;
            if ((part & 0x80) == 0) return value;
        }
        throw new IOException("var-int is too long");
    }

    /**
     * One frame off the wire: its packet id always, its body only when {@link Frame#body()} is asked.
     */
    private final class Frame {
        final byte[] frame;
        final int wireBytes;
        final int id;
        /** Where the packet starts in {@link #frame}, past the data length; -1 while compressed. */
        private final int start;
        private final int dataLength;
        private byte @Nullable [] body;
        private int payloadOffset;

        Frame(byte[] frame, int wireBytes) throws IOException {
            this.frame = frame;
            this.wireBytes = wireBytes;
            if (compressionThreshold < 0) {
                this.dataLength = 0;
                this.start = 0;
                this.id = varIntAt(frame, 0);
                return;
            }
            final int[] cursor = {0};
            this.dataLength = varIntAt(frame, cursor);
            if (dataLength == 0) {
                this.start = cursor[0];
                this.id = varIntAt(frame, start);
            } else {
                this.start = cursor[0];
                inflater.reset();
                inflater.setInput(frame, start, frame.length - start);
                try {
                    final int produced = inflater.inflate(head, 0, Math.min(head.length, dataLength));
                    this.id = varIntAt(Arrays.copyOf(head, produced), 0);
                } catch (DataFormatException e) {
                    throw new IOException("a compressed frame does not inflate", e);
                }
            }
        }

        byte[] body() throws IOException {
            if (body == null) {
                if (dataLength == 0) {
                    body = frame;
                    payloadOffset = start + varIntSize(id);
                } else {
                    inflater.reset();
                    inflater.setInput(frame, start, frame.length - start);
                    final byte[] data = new byte[dataLength];
                    try {
                        int written = 0;
                        while (written < dataLength) {
                            final int read = inflater.inflate(data, written, dataLength - written);
                            if (read == 0) break;
                            written += read;
                        }
                    } catch (DataFormatException e) {
                        throw new IOException("a compressed frame does not inflate", e);
                    }
                    body = data;
                    payloadOffset = varIntSize(id);
                }
            }
            return body;
        }

        int payloadOffset() throws IOException {
            body();
            return payloadOffset;
        }
    }

    private Frame readFrame() throws IOException {
        final int length = readVarInt();
        final byte[] frame = in.readNBytes(length);
        if (frame.length != length) throw new EOFException("the server closed the connection");
        midFrame = false;
        return new Frame(frame, length + varIntSize(length));
    }

    private ServerPacket readFully(ConnectionState readState) throws IOException {
        final Frame frame = readFrame();
        stats.received(readState, frame.id, frame.wireBytes);
        final ServerPacket packet = Protocol.decode(readState, frame.id, frame.body(), frame.payloadOffset());
        if (packet == null) throw new IOException("cannot read packet " + frame.id + " in " + readState);
        return packet;
    }

    private static int varIntAt(byte[] data, int offset) {
        return varIntAt(data, new int[]{offset});
    }

    private static int varIntAt(byte[] data, int[] cursor) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (cursor[0] >= data.length) throw new IllegalStateException("truncated var-int");
            final int part = data[cursor[0]++] & 0xFF;
            value |= (part & 0x7F) << shift;
            if ((part & 0x80) == 0) return value;
        }
        throw new IllegalStateException("var-int is too long");
    }

    private static int varIntSize(int value) {
        int size = 1;
        int remaining = value >>> 7;
        while (remaining != 0) {
            size++;
            remaining >>>= 7;
        }
        return size;
    }

    /** The reason key of a kick: the translate key of a reason key, the text of a plain one. */
    static String key(Component message) {
        return switch (message) {
            case TranslatableComponent translatable -> translatable.key();
            case TextComponent text -> text.content();
            default -> message.getClass().getSimpleName();
        };
    }

    /** The server ended the session on purpose, with a reason. */
    static final class Kicked extends IOException {
        private static final long serialVersionUID = 1L;

        Kicked(String reason) {
            super(reason);
        }
    }
}
