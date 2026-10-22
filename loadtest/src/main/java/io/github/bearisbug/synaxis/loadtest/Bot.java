package io.github.bearisbug.synaxis.loadtest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.crypto.KeyUtils;
import io.github.bearisbug.synaxis.crypto.LoginCrypt;
import io.github.bearisbug.synaxis.game.protocol.C2SHello;
import io.github.bearisbug.synaxis.game.protocol.ProtocolSchema;
import io.github.bearisbug.synaxis.loadtest.Profile.GateWalk;
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
import io.github.bearisbug.synaxis.network.packet.server.common.CookieStorePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.KeepAlivePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PingPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.TransferPacket;
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
import io.github.bearisbug.synaxis.network.packet.server.play.DestroyEntitiesPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerPositionAndLookPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SpawnEntityPacket;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
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
 * It follows a {@code Transfer} the way a client does ({@code docs/DESIGN-M3.md} §26):
 * the cookies stored during play are kept, the connection is dropped, a new one to the given
 * address says intent transfer and answers the new server's cookie requests with them, and plays on
 * as the same bot. A gate bot times each try from its first position in a frame to the 9th chunk
 * on the new connection. A try that fails on the way, the next server refusing the bot or the
 * connection breaking before that chunk, counts under its reason, and the bot logs in again at
 * {@code --host} and {@code --port} without the cookies, as a player reconnects. Every connection
 * leaves an entry in the run file: how it was opened, where the server spawned the bot and the
 * cookies stored on it.
 * <p>
 * One virtual thread reads, one moves. A frame is only inflated as far as its packet id unless the
 * bot has to answer it, so a bot costs little more than the bytes it receives.
 */
final class Bot {

    /** What every bot of a run shares. */
    record Settings(String host, int port, Profile profile, boolean encryption, float chunksPerTick, boolean hello,
                    double warmupSeconds, int timeoutMillis, List<Profile.Frame> gates, double dwellSeconds,
                    long noTransferNanos) {
    }

    private static final String HANDSHAKE_CHANNEL = "synaxis:handshake";
    private static final int MOD_VERSION = 1;
    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    /** How long a gate bot waits in a frame for a Transfer before it counts a no-transfer. */
    static final long NO_TRANSFER_NANOS = TimeUnit.SECONDS.toNanos(30);
    /** The reason a try that got no Transfer in time counts under. */
    private static final String NO_TRANSFER = "no-transfer";
    /** The chunk of a new connection that completes a transfer: the last of the vanilla first batch. */
    private static final int FIRST_BATCH = 9;
    /**
     * The farthest one position packet goes from where the server has the bot. The server turns back a
     * packet more than 64 blocks away ({@code docs/DESIGN-PROD.md} {@code API-PROD-030}), and the profile
     * runs ahead of the server when the moving thread stalls, so the bot walks to it in steps of this
     * length, as {@code tools/loadtest/PacketFlood.java} and {@code soak.py} do.
     */
    private static final double STEP_LIMIT = 32;

    private static final int KEEP_ALIVE = Protocol.serverId(ConnectionState.PLAY, KeepAlivePacket.class);
    private static final int PING = Protocol.serverId(ConnectionState.PLAY, PingPacket.class);
    private static final int TELEPORT = Protocol.serverId(ConnectionState.PLAY, PlayerPositionAndLookPacket.class);
    private static final int BATCH_START = Protocol.serverId(ConnectionState.PLAY, ChunkBatchStartPacket.class);
    private static final int BATCH_FINISHED = Protocol.serverId(ConnectionState.PLAY, ChunkBatchFinishedPacket.class);
    private static final int CHUNK_DATA = Protocol.serverId(ConnectionState.PLAY, ChunkDataPacket.class);
    private static final int DISCONNECT = Protocol.serverId(ConnectionState.PLAY, DisconnectPacket.class);
    private static final int START_CONFIGURATION = Protocol.serverId(ConnectionState.PLAY, StartConfigurationPacket.class);
    private static final int SPAWN_ENTITY = Protocol.serverId(ConnectionState.PLAY, SpawnEntityPacket.class);
    private static final int DESTROY_ENTITIES = Protocol.serverId(ConnectionState.PLAY, DestroyEntitiesPacket.class);
    private static final int TRANSFER = Protocol.serverId(ConnectionState.PLAY, TransferPacket.class);
    private static final int COOKIE_STORE = Protocol.serverId(ConnectionState.PLAY, CookieStorePacket.class);
    private static final Set<Integer> ANSWERED = Set.of(KEEP_ALIVE, PING, TELEPORT, BATCH_FINISHED, DISCONNECT);

    final int index;
    final String username;
    private final Settings settings;
    private final BotStats stats;
    private @Nullable Socket socket;
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
    int lastConnection;

    private volatile long playSince;
    private volatile long closedAt;
    private volatile boolean closed;
    private volatile long endNanos;
    private final AtomicBoolean lost = new AtomicBoolean();
    private long chunks;
    private long batchStarted;
    // The entities the server has this bot see, only touched by the reading thread
    private final Set<Integer> visible = new HashSet<>();
    private volatile int visibleCount;
    private volatile boolean moving;

    /** The cookies the servers stored, answered on the next connection as a client does. Reading thread only. */
    private final Map<String, byte[]> cookies = new HashMap<>();
    /** The run file entry of the current connection and the cookies stored on it; reading thread only. */
    private @Nullable JsonObject log;
    private JsonObject logCookies = new JsonObject();
    /** The connections made so far, the first login included. */
    private final AtomicInteger connectionCount = new AtomicInteger();
    /** The chunk packets of the current connection, only touched by the reading thread. */
    private int connectionChunks;
    /** Where the bot moves on the current connection; null until the server spawns it there. */
    private volatile @Nullable Leg leg;
    /** When the running try of a gate bot started, {@link System#nanoTime()}; 0 when none runs. */
    private final AtomicLong tryStart = new AtomicLong();
    /**
     * Where the server has the bot: the position it sent last, or where the last teleport put it, the
     * first one of a connection included. Under {@link #writeLock}.
     */
    private double[] serverPosition = new double[3];
    /** What the moves take the time from: {@link System#nanoTime()}, a clock a test jumps to stall them. */
    private final LongSupplier clock;

    /** One spawn: where and when, and the walk of a gate bot from there. */
    private record Leg(double x, double y, double z, long since, @Nullable GateWalk walk) {
    }

    Bot(int index, Settings settings, BotStats stats) {
        this(index, settings, stats, System::nanoTime);
    }

    Bot(int index, Settings settings, BotStats stats, LongSupplier clock) {
        this.index = index;
        this.username = String.format(Locale.ROOT, "bot%05d", index);
        this.settings = settings;
        this.stats = stats;
        this.clock = clock;
    }

    /**
     * Logs in, then plays until {@code endNanos} or until the server lets go, following every
     * Transfer to the next server. Blocks; runs on the bot's own thread.
     *
     * @param endNanos when the run ends, {@link System#nanoTime()}
     */
    void run(long endNanos) {
        this.endNanos = endNanos;
        final long connectStart = System.nanoTime();
        Socket connection;
        try {
            connection = open(settings.host(), settings.port(), ClientHandshakePacket.Intent.LOGIN, connectStart);
        } catch (IOException | RuntimeException e) {
            BotStats.count(stats.loginFailures, reason(e));
            close();
            logged();
            return;
        }
        stats.loggedIn.increment();
        playSince = System.nanoTime();
        try {
            while (true) {
                try {
                    connection.setSoTimeout(1_000);
                    final TransferPacket transfer = play(endNanos);
                    if (transfer == null) break;
                    // The same bot on the next server: its try, if any, runs on until the 9th chunk there
                    connection = open(transfer.host(), transfer.port(), ClientHandshakePacket.Intent.TRANSFER, 0);
                } catch (IOException | RuntimeException e) {
                    if (!tryFailed(e)) throw e;
                    // As a player reconnects after a failed transfer: at the start, with no cookie kept
                    cookies.clear();
                    connection = open(settings.host(), settings.port(), ClientHandshakePacket.Intent.LOGIN, 0);
                }
            }
        } catch (IOException | RuntimeException e) {
            lose(e);
        } finally {
            close();
            logged();
            account();
        }
    }

    /**
     * Connects, logs in and configures: the bot plays on the returned connection next.
     *
     * @param connectStart see {@link #login}
     */
    private Socket open(String host, int port, ClientHandshakePacket.Intent intent, long connectStart)
            throws IOException {
        final Socket next = connect(host, port, intent);
        login(host, port, intent, connectStart);
        configure();
        return next;
    }

    /**
     * Ends the running try of a gate bot as failed, under the reason of {@code e}, unless the run
     * closed the bot or its time was up.
     *
     * @return whether a try failed: the bot was on its way to another server and starts over
     */
    private boolean tryFailed(Exception e) {
        if (closed || System.nanoTime() >= endNanos || tryStart.getAndSet(0) == 0) return false;
        BotStats.count(stats.transferFailures, tryFailure(e));
        return true;
    }

    /**
     * Counts the bot lost once, unless the run closed it or its time was up. A try it was on failed
     * for the same reason.
     */
    private void lose(Exception e) {
        if (closed || System.nanoTime() >= endNanos || !lost.compareAndSet(false, true)) return;
        stats.lost.increment();
        BotStats.count(stats.kicks, reason(e));
        if (tryStart.getAndSet(0) != 0) BotStats.count(stats.transferFailures, tryFailure(e));
    }

    private static String reason(Exception e) {
        if (e instanceof Kicked kicked) return kicked.getMessage();
        return "closed: " + e.getClass().getSimpleName();
    }

    /** Why a try failed: the reason key without its namespace ({@code shard-full}), else as {@link #reason}. */
    private static String tryFailure(Exception e) {
        if (!(e instanceof Kicked kicked)) return reason(e);
        return kicked.key.startsWith("synaxis:") ? kicked.key.substring("synaxis:".length()) : kicked.key;
    }

    // -- login and configuration ------------------------------------------------------------

    /**
     * Opens a connection to {@code host:port} and makes it the bot's. The one before it, from which a
     * Transfer came, is dropped first: nothing is sent on it once the new one is being set up.
     *
     * @return the new connection
     */
    private Socket connect(String host, int port, ClientHandshakePacket.Intent intent) throws IOException {
        final Socket next = new Socket();
        final Socket previous;
        final int number;
        synchronized (writeLock) {
            if (closed) throw new EOFException("closed");
            previous = socket;
            socket = next;
            leg = null;
            state = ConnectionState.LOGIN;
            compressionThreshold = -1;
            number = connectionCount.incrementAndGet();
        }
        if (previous != null) closeQuietly(previous);
        logged();
        final JsonObject entry = new JsonObject();
        entry.addProperty("bot", index);
        entry.addProperty("connection", number);
        entry.addProperty("intent", intent == ClientHandshakePacket.Intent.TRANSFER ? "transfer" : "login");
        entry.addProperty("address", host + ":" + port);
        log = entry;
        logCookies = new JsonObject();
        connectionChunks = 0;
        batchStarted = 0;
        midFrame = false;
        visible.clear();
        visibleCount = 0;
        next.setTcpNoDelay(true);
        next.connect(new InetSocketAddress(host, port), settings.timeoutMillis());
        next.setSoTimeout(settings.timeoutMillis());
        synchronized (writeLock) {
            in = new BufferedInputStream(next.getInputStream(), 1 << 16);
            out = next.getOutputStream();
        }
        return next;
    }

    /**
     * @param connectStart when the connection was opened, 0 for a transfer: the login time is that of
     *                     the bot's first login only
     */
    private void login(String host, int port, ClientHandshakePacket.Intent intent, long connectStart)
            throws IOException {
        send(ConnectionState.HANDSHAKE,
                new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION, host, port, intent));
        send(ConnectionState.LOGIN, new ClientLoginStartPacket(username, GameProfile.offlineUuid(username)));
        while (true) {
            final ServerPacket packet = readFully(ConnectionState.LOGIN);
            switch (packet) {
                case SetCompressionPacket compression -> compressionThreshold = compression.threshold();
                case EncryptionRequestPacket request -> encrypt(request);
                case LoginPluginRequestPacket request -> send(ConnectionState.LOGIN, new ClientLoginPluginResponsePacket(
                        request.messageId(), settings.hello() && request.channel().equals(HANDSHAKE_CHANNEL) ? hello() : null));
                case CookieRequestPacket cookie -> send(ConnectionState.LOGIN,
                        new ClientCookieResponsePacket(cookie.key(), cookies.get(cookie.key())));
                case LoginSuccessPacket _ -> {
                    if (connectStart != 0) stats.loginMillis.record((System.nanoTime() - connectStart) / 1e6);
                    send(ConnectionState.LOGIN, new ClientLoginAcknowledgedPacket());
                    state = ConnectionState.CONFIGURATION;
                    return;
                }
                case LoginDisconnectPacket refused -> throw new Kicked("login refused: ", key(refused.kickMessage()));
                default -> {
                    // nothing else is answered during login
                }
            }
        }
    }

    private static byte[] hello() {
        return new C2SHello(MOD_VERSION, ProtocolSchema.SCHEMA_VERSION).encode();
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
                case CookieRequestPacket cookie -> send(ConnectionState.CONFIGURATION,
                        new ClientCookieResponsePacket(cookie.key(), cookies.get(cookie.key())));
                case CodeOfConductPacket _ -> send(ConnectionState.CONFIGURATION, new ClientAcceptCodeOfConductPacket());
                case FinishConfigurationPacket _ -> {
                    send(ConnectionState.CONFIGURATION, new ClientFinishConfigurationPacket());
                    state = ConnectionState.PLAY;
                    return;
                }
                case DisconnectPacket kicked -> throw new Kicked("configuration: ", key(kicked.message()));
                default -> {
                    // registries, tags and the rest of the configuration stream need no answer
                }
            }
        }
    }

    // -- play -------------------------------------------------------------------------------

    /**
     * Plays on the current connection until the run ends or the server lets go.
     *
     * @return the Transfer that ended it, null for any other end
     */
    private @Nullable TransferPacket play(long endNanos) throws IOException {
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
                synchronized (writeLock) {
                    // The ack and the state under one lock: a position the moving thread sent between them would
                    // reach the server in configuration, which drops the connection for it
                    send(ConnectionState.PLAY, new ClientConfigurationAckPacket());
                    state = ConnectionState.CONFIGURATION;
                }
            } else if (id == SPAWN_ENTITY || id == DESTROY_ENTITIES) {
                track(Protocol.decode(ConnectionState.PLAY, id, frame.body(), frame.payloadOffset()));
            } else if (id == TRANSFER) {
                final ServerPacket packet =
                        Protocol.decode(ConnectionState.PLAY, id, frame.body(), frame.payloadOffset());
                if (packet instanceof TransferPacket transfer) return transfer;
                throw new IOException("cannot read the Transfer packet");
            } else if (id == COOKIE_STORE) {
                final ServerPacket packet =
                        Protocol.decode(ConnectionState.PLAY, id, frame.body(), frame.payloadOffset());
                if (packet instanceof CookieStorePacket cookie) {
                    cookies.put(cookie.key(), cookie.value());
                    logCookies.addProperty(cookie.key(), Base64.getEncoder().encodeToString(cookie.value()));
                }
            } else if (ANSWERED.contains(id)) {
                answer(Protocol.decode(ConnectionState.PLAY, id, frame.body(), frame.payloadOffset()));
            }
        }
        return null;
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
            case CookieRequestPacket cookie -> send(ConnectionState.CONFIGURATION,
                    new ClientCookieResponsePacket(cookie.key(), cookies.get(cookie.key())));
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
                synchronized (writeLock) {
                    // Every position sent after the confirmation starts from where the teleport put the bot
                    serverPosition = new double[]{teleport.position().x(), teleport.position().y(), teleport.position().z()};
                    send(ConnectionState.PLAY, new ClientTeleportConfirmPacket(teleport.teleportId()));
                }
                if (leg == null) spawned(teleport);
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

    /**
     * The first teleport on a connection is where the server spawned the bot: it moves from there,
     * a gate bot on a new walk.
     */
    private void spawned(PlayerPositionAndLookPacket teleport) throws IOException {
        send(ConnectionState.PLAY, new ClientPlayerLoadedPacket());
        final long now = clock.getAsLong();
        final double x = teleport.position().x();
        final double y = teleport.position().y();
        final double z = teleport.position().z();
        if (log != null) {
            final JsonArray position = new JsonArray();
            position.add(x);
            position.add(y);
            position.add(z);
            log.add("first_position", position);
        }
        leg = new Leg(x, y, z, now, settings.profile() != Profile.GATE ? null
                : new GateWalk(settings.gates(), x, y, z, now, settings.dwellSeconds(), settings.noTransferNanos()));
        if (!moving) {
            moving = true;
            Thread.ofVirtual().name("bot-move-" + index).start(this::move);
        }
    }

    /** Keeps count of the entities the server has this bot see ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-024}). */
    private void track(@Nullable ServerPacket packet) {
        switch (packet) {
            case SpawnEntityPacket spawn -> visible.add(spawn.entityId());
            case DestroyEntitiesPacket destroy -> {
                for (int entityId : destroy.entityIds()) visible.remove(entityId);
            }
            case null, default -> {
                // not an entity packet the codec could read
            }
        }
        visibleCount = visible.size();
    }

    /** How many entities the server has this bot see right now. */
    int visibleEntities() {
        return visibleCount;
    }

    private void chunk(int wireBytes) {
        chunks++;
        stats.chunkPackets.increment();
        stats.chunkBytes.add(wireBytes);
        if ((System.nanoTime() - playSince) / 1e9 >= settings.warmupSeconds()) {
            stats.steadyChunkPackets.increment();
            stats.steadyChunkBytes.add(wireBytes);
        }
        // The 9th chunk on a connection a Transfer opened ends the try that brought the bot here
        if (++connectionChunks == FIRST_BATCH && connectionCount.get() > 1) {
            final long started = tryStart.getAndSet(0);
            if (started != 0) stats.transferMillis.record((System.nanoTime() - started) / 1e6);
        }
    }

    /**
     * Moves every tick along the profile: a position packet and a tick end, like a walking client.
     * Stands still from a Transfer until the next server spawns the bot.
     */
    private void move() {
        final long start = System.nanoTime();
        long tick = 0;
        // The leg whose connection broke under this thread; a Transfer the read loop is still to see brings a new one
        Leg broken = null;
        try {
            while (!closed) {
                final long deadline = start + ++tick * TICK_NANOS;
                final long wait = deadline - System.nanoTime();
                if (wait > 0) LockSupport.parkNanos(wait);
                if (closed) return;
                final Leg current = leg;
                if (current == null || current == broken) continue;
                try {
                    moveOn(current);
                } catch (IOException _) {
                    // Left open: the read loop sees the same broken socket and reports it with the server's reason
                    broken = current;
                }
            }
        } catch (RuntimeException e) {
            lose(e);
            close();
        }
    }

    /**
     * One tick on a leg: where the profile has the bot now, at most {@link #STEP_LIMIT} blocks from
     * where the server has it, sent unless the leg's connection is gone or back in configuration. A
     * gate walk only moves on a tick whose position goes out, so that a try starts with the first
     * position in a frame the bot sends. After a stall the bot catches up with its walk over a few
     * ticks, and a try that starts meanwhile starts that much before the bot is in the frame.
     */
    private void moveOn(Leg current) throws IOException {
        final long now = clock.getAsLong();
        final boolean onGround = settings.profile().lift == 0;
        synchronized (writeLock) {
            if (leg != current || state != ConnectionState.PLAY) return;
            final double[] at;
            final GateWalk walk = current.walk();
            if (walk != null) {
                final GateWalk.Step step = walk.next(now);
                if (step.noTransfer() && tryStart.getAndSet(0) != 0) BotStats.count(stats.transferFailures, NO_TRANSFER);
                // Started before the packet goes out, so that the Transfer it brings finds the try running
                if (step.entered()) tryStart.compareAndSet(0, System.nanoTime());
                at = new double[]{step.x(), step.y(), step.z()};
            } else {
                at = settings.profile().position(index, current.x(), current.y(), current.z(),
                        (now - current.since()) / 1e9);
            }
            final double[] next = toward(serverPosition, at);
            serverPosition = next;
            send(ConnectionState.PLAY, new ClientPlayerPositionPacket(new Vec(next[0], next[1], next[2]),
                    (byte) (onGround ? ClientPlayerPositionPacket.FLAG_ON_GROUND : 0)));
            send(ConnectionState.PLAY, new ClientTickEndPacket());
        }
    }

    /** The point on the way from {@code from} to {@code to} at most {@link #STEP_LIMIT} blocks from {@code from}. */
    private static double[] toward(double[] from, double[] to) {
        final double dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
        final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= STEP_LIMIT) return to;
        final double scale = STEP_LIMIT / distance;
        return new double[]{from[0] + dx * scale, from[1] + dy * scale, from[2] + dz * scale};
    }

    /** Hands the run file entry of the connection that ended, if any, to {@link BotStats#connectionLog}. */
    private void logged() {
        final JsonObject entry = log;
        if (entry == null) return;
        entry.add("stored_cookies", logCookies);
        stats.connectionLog.add(entry);
        log = null;
    }

    private void account() {
        final long since = playSince;
        if (since == 0) return;
        final double seconds = (closedAt - since) / 1e9;
        stats.connectionSeconds.add(seconds);
        stats.steadyConnectionSeconds.add(Math.max(0, seconds - settings.warmupSeconds()));
        stats.chunksPerBot.record((double) chunks);
    }

    /** Whether the bot is spawned on a server: not before its first spawn, nor between a Transfer and the next. */
    boolean inPlay() {
        return playSince != 0 && !closed && leg != null;
    }

    long playSince() {
        return playSince;
    }

    /** How many connections the bot has made, one more with every Transfer it followed. */
    int connections() {
        return connectionCount.get();
    }

    void close() {
        final Socket current;
        synchronized (writeLock) {
            if (closed) return;
            closed = true;
            closedAt = System.nanoTime();
            current = socket;
        }
        if (current != null) closeQuietly(current);
    }

    private static void closeQuietly(Socket socket) {
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

        /** The reason key, {@link #key(Component)} of the server's message. */
        final String key;

        Kicked(String key) {
            this("", key);
        }

        /** @param context what the bot was doing, the start of the message */
        Kicked(String context, String key) {
            super(context + key);
            this.key = key;
        }
    }
}
