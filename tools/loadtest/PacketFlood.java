import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * usage: java tools/loadtest/PacketFlood.java [options]
 *
 * An inbound packet generator for the one quantitative goal `docs/DESIGN.md` §15 leaves open:
 * "50 players, 5,000 inbound packets per second, no loss". The Python harness
 * `tools/loadtest/soak.py` cannot reach it - one selector loop in one interpreter tops out around
 * 2,880 packets per second - so this tool drives the same four client packets from the JDK, one
 * sender thread and one reader thread per connection, and reports the rate it *achieved* rather
 * than the rate it was asked for.
 *
 * It is a tool, not part of the engine: it is built by the single file source launcher, lives in
 * no source set, and is not on any module path.
 *
 * Packet ids are never hardcoded. Exactly like `tools/protocol-probe/probe.py`, they are the index
 * of the packet class inside the registry lists of
 * `src/main/java/io/github/bearisbug/synaxis/network/packet/PacketVanilla.java`, parsed at
 * start-up, so the tool follows the repository instead of a remembered protocol dump.
 *
 * Run it from the worktree root against a server already listening on 25565:
 *
 *     java tools/loadtest/PacketFlood.java --connections 50 --hz 100 --seconds 330 \
 *          --out /tmp/synaxis-flood/nfr
 *
 * Against a gameplay mode server add `--hello true`, which sends the C2S_HELLO of REQ-M2-008 in
 * the configuration phase; without it every connection is kicked three seconds in.
 *
 * `--auth` names the login mode of the server (`synaxis.app.auth`, `docs/DESIGN-PROD.md`
 * `API-PROD-001`): `offline` (the default) expects no encryption request; `offline-encrypted` and
 * `online` answer it with RSA/PKCS#1 and run AES/CFB8 both ways, the latter against a server whose
 * session server is `tools/fake-sessionserver` in `allow-all` mode, since the bots have no Mojang
 * account and never join a session.
 *
 * Every connection sends `--hz` position packets per second, which is what makes the total rate
 * `--connections * --hz`. Keep `--hz` well under the drain the engine grants one player
 * (`ServerConfig#playerPacketPerTick`, 50 per tick, so 1,000 per second at 20 TPS): above it the
 * player packet queue fills and the engine kicks with `synaxis:protocol-error`, which is a
 * limiter firing rather than the engine failing.
 */
public final class PacketFlood {

    private static final int PROTOCOL_VERSION = 776;
    private static final String ECHO_CHANNEL = "synaxis:main";
    private static final String HANDSHAKE_CHANNEL = "synaxis:handshake";
    /** C2S_HELLO of API-M2-002; the mod version the tool announces is the only one M2 defines. */
    private static final int C2S_HELLO = 0x01;
    private static final int MOD_VERSION = 1;
    private static final double FLOOR_Y = 40.0;
    /** Blocks per second each connection walks along its circle, the same figure soak.py uses. */
    private static final double WALK_SPEED = 4.0;
    /**
     * The farthest one position packet goes from where the server has the player. The server turns
     * back a packet more than 64 blocks away ({@code docs/DESIGN-PROD.md} {@code API-PROD-030}), so
     * the first point of a circle, up to 72 blocks from the spawn, is walked to in steps of this length.
     */
    private static final double STEP_LIMIT = 32.0;

    private PacketFlood() {
    }

    // ---------------------------------------------------------------------------------------
    // Packet ids derived from the repository
    // ---------------------------------------------------------------------------------------

    /** Registry field name to the ordered packet class names it registers. */
    private static Map<String, List<String>> registries = Map.of();

    /** The wire schema version, read out of the protocol module rather than remembered. */
    private static int schemaVersion;

    private static List<String> registryBlock(String source, String field) {
        final Matcher matcher = Pattern.compile("\\b" + field + "\\s*=\\s*(?:PacketRegistry\\.<[^;]*?>)?registry\\(")
                .matcher(source);
        if (!matcher.find()) throw new IllegalStateException("cannot find packet registry " + field);
        int depth = 1;
        int index = matcher.end();
        while (depth > 0) {
            final char character = source.charAt(index++);
            if (character == '(') depth++;
            else if (character == ')') depth--;
        }
        final String block = source.substring(matcher.end(), index - 1);
        final Matcher entries = Pattern.compile("entry\\(\\s*([A-Za-z0-9_]+)\\.class").matcher(block);
        final List<String> names = new ArrayList<>();
        while (entries.find()) names.add(entries.group(1));
        return names;
    }

    private static void loadRegistries(Path repository) throws IOException {
        final Path packetVanilla = repository.resolve(
                "src/main/java/io/github/bearisbug/synaxis/network/packet/PacketVanilla.java");
        if (!Files.isRegularFile(packetVanilla))
            throw new IllegalStateException("run this from the worktree root, " + packetVanilla + " is not there");
        final String source = Files.readString(packetVanilla);
        final Path protocolSchema = repository.resolve(
                "protocol/src/main/java/io/github/bearisbug/synaxis/game/protocol/ProtocolSchema.java");
        final Matcher schema = Pattern.compile("SCHEMA_VERSION\\s*=\\s*(\\d+)")
                .matcher(Files.readString(protocolSchema));
        if (!schema.find()) throw new IllegalStateException("cannot find SCHEMA_VERSION in " + protocolSchema);
        schemaVersion = Integer.parseInt(schema.group(1));
        final Map<String, List<String>> loaded = new LinkedHashMap<>();
        for (String field : List.of("CLIENT_HANDSHAKE", "CLIENT_LOGIN", "CLIENT_CONFIGURATION", "CLIENT_PLAY",
                "SERVER_LOGIN", "SERVER_CONFIGURATION", "SERVER_PLAY"))
            loaded.put(field, registryBlock(source, field));
        registries = loaded;
    }

    private static int clientId(String registry, String packet) {
        final int index = registries.get(registry).indexOf(packet);
        if (index < 0) throw new IllegalStateException(packet + " is not a client packet of " + registry);
        return index;
    }

    private static String serverName(String registry, int packetId) {
        final List<String> packets = registries.get(registry);
        return packetId >= 0 && packetId < packets.size() ? packets.get(packetId) : "Unknown(" + packetId + ")";
    }

    // ---------------------------------------------------------------------------------------
    // Buffer primitives
    // ---------------------------------------------------------------------------------------

    private static final class Writer {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);

        Writer varInt(int value) {
            int remaining = value;
            while (true) {
                final int chunk = remaining & 0x7F;
                remaining >>>= 7;
                if (remaining != 0) {
                    buffer.write(chunk | 0x80);
                } else {
                    buffer.write(chunk);
                    return this;
                }
            }
        }

        Writer raw(byte[] data) {
            buffer.writeBytes(data);
            return this;
        }

        Writer u8(int value) {
            buffer.write(value & 0xFF);
            return this;
        }

        Writer u16(int value) {
            return u8(value >> 8).u8(value);
        }

        Writer i64(long value) {
            for (int shift = 56; shift >= 0; shift -= 8) u8((int) (value >> shift));
            return this;
        }

        Writer f32(float value) {
            final int bits = Float.floatToIntBits(value);
            for (int shift = 24; shift >= 0; shift -= 8) u8(bits >> shift);
            return this;
        }

        Writer f64(double value) {
            return i64(Double.doubleToLongBits(value));
        }

        Writer string(String value) {
            final byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            return varInt(encoded.length).raw(encoded);
        }

        byte[] bytes() {
            return buffer.toByteArray();
        }
    }

    private static final class Reader {
        private final byte[] data;
        private int cursor;

        Reader(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length - cursor;
        }

        int u8() {
            if (cursor >= data.length) throw new IllegalStateException("truncated packet");
            return data[cursor++] & 0xFF;
        }

        byte[] raw(int count) {
            if (remaining() < count) throw new IllegalStateException("truncated packet");
            final byte[] slice = Arrays.copyOfRange(data, cursor, cursor + count);
            cursor += count;
            return slice;
        }

        int varInt() {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                final int part = u8();
                value |= (part & 0x7F) << shift;
                if ((part & 0x80) == 0) return value;
            }
            throw new IllegalStateException("var-int is too long");
        }

        long i64() {
            long value = 0;
            for (int index = 0; index < 8; index++) value = (value << 8) | u8();
            return value;
        }

        double f64() {
            return Double.longBitsToDouble(i64());
        }

        String string() {
            return new String(raw(varInt()), StandardCharsets.UTF_8);
        }
    }

    /**
     * Reads a network nbt component (unnamed root) and returns the reason it carries: the
     * {@code translate} key of a reason key (`docs/DESIGN.md` §14), or the literal {@code text} of
     * the plain components the engine still kicks with.
     */
    private static String componentReason(Reader reader) {
        if (reader.u8() != 10) return "<not a compound>";
        String text = null;
        while (true) {
            final int tag = reader.u8();
            if (tag == 0) return text != null ? text : "<no translate or text key>";
            final String name = new String(reader.raw((reader.u8() << 8) | reader.u8()), StandardCharsets.UTF_8);
            if (tag == 8 && (name.equals("translate") || name.equals("text"))) {
                final String value = new String(reader.raw((reader.u8() << 8) | reader.u8()),
                        StandardCharsets.UTF_8);
                if (name.equals("translate")) return value;
                text = value;
            } else {
                skipTag(reader, tag);
            }
        }
    }

    private static void skipTag(Reader reader, int tag) {
        switch (tag) {
            case 1 -> reader.raw(1);
            case 2 -> reader.raw(2);
            case 3, 5 -> reader.raw(4);
            case 4, 6 -> reader.raw(8);
            case 7 -> reader.raw(readInt(reader));
            case 8 -> reader.raw((reader.u8() << 8) | reader.u8());
            case 9 -> {
                final int element = reader.u8();
                final int size = readInt(reader);
                for (int index = 0; index < size; index++) skipTag(reader, element);
            }
            case 10 -> {
                while (true) {
                    final int entry = reader.u8();
                    if (entry == 0) return;
                    reader.raw((reader.u8() << 8) | reader.u8());
                    skipTag(reader, entry);
                }
            }
            case 11 -> reader.raw(readInt(reader) * 4);
            case 12 -> reader.raw(readInt(reader) * 8);
            default -> throw new IllegalStateException("unknown nbt tag " + tag);
        }
    }

    private static int readInt(Reader reader) {
        return (reader.u8() << 24) | (reader.u8() << 16) | (reader.u8() << 8) | reader.u8();
    }

    /** The offline mode uuid a vanilla client sends, a name based md5 uuid. */
    private static byte[] offlineUuid(String username) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        final byte[] bytes = digest.digest(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        bytes[6] = (byte) ((bytes[6] & 0x0F) | 0x30);
        bytes[8] = (byte) ((bytes[8] & 0x3F) | 0x80);
        return bytes;
    }

    // ---------------------------------------------------------------------------------------
    // Metrics
    // ---------------------------------------------------------------------------------------

    private static final class Metrics {
        /** Inbound packets the tool actually wrote to a socket, the number the report quotes. */
        final LongAdder sent = new LongAdder();
        final LongAdder keepAlives = new LongAdder();
        final LongAdder teleportConfirms = new LongAdder();
        final LongAdder echoesSent = new LongAdder();
        final Map<String, Long> disconnects = new ConcurrentHashMap<>();
        final List<Double> roundTripsMillis = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong connected = new AtomicLong();
        final LongAdder lost = new LongAdder();
        final List<String> losses = Collections.synchronizedList(new ArrayList<>());
    }

    // ---------------------------------------------------------------------------------------
    // One connection
    // ---------------------------------------------------------------------------------------

    private static final class Connection {
        private final Options options;
        private final Metrics metrics;
        private final String username;
        private final int index;
        private final Socket socket = new Socket();
        private final Object writeLock = new Object();
        private InputStream input;
        private OutputStream output;
        private int compressionThreshold = -1;
        /** True while a frame is only partly read; login and readLoop share one thread per connection. */
        private boolean midFrame;
        private volatile boolean closed;
        private volatile long pendingEchoNanos = -1;
        private final double radius;
        private final double phase;
        /** Where the server has the player: the last position sent, or the one a teleport put it at. */
        private volatile double[] at = {0.5, FLOOR_Y, 0.5};

        Connection(Options options, Metrics metrics, int index) {
            this.options = options;
            this.metrics = metrics;
            this.index = index;
            this.username = String.format(Locale.ROOT, "flood%04d", index);
            this.radius = 16.0 + (index % 8) * 8.0;
            this.phase = index * (2 * Math.PI / Math.max(1, options.connections));
        }

        // -- framing ---------------------------------------------------------------------

        private void write(String registry, String packet, byte[] payload) throws IOException {
            final byte[] body = new Writer().varInt(clientId(registry, packet)).raw(payload).bytes();
            final byte[] frame;
            if (compressionThreshold < 0) {
                frame = new Writer().varInt(body.length).raw(body).bytes();
            } else {
                final byte[] data;
                if (body.length >= compressionThreshold) {
                    data = new Writer().varInt(body.length).raw(deflate(body)).bytes();
                } else {
                    data = new Writer().varInt(0).raw(body).bytes();
                }
                frame = new Writer().varInt(data.length).raw(data).bytes();
            }
            synchronized (writeLock) {
                output.write(frame);
                output.flush();
            }
            metrics.sent.increment();
        }

        private static byte[] deflate(byte[] body) {
            final Deflater deflater = new Deflater();
            try {
                deflater.setInput(body);
                deflater.finish();
                final ByteArrayOutputStream out = new ByteArrayOutputStream(body.length);
                final byte[] chunk = new byte[1024];
                while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk));
                return out.toByteArray();
            } finally {
                deflater.end();
            }
        }

        private static byte[] inflate(byte[] data, int expected) {
            final Inflater inflater = new Inflater();
            try {
                inflater.setInput(data);
                final byte[] out = new byte[expected];
                int written = 0;
                while (written < expected) {
                    final int read = inflater.inflate(out, written, expected - written);
                    if (read == 0) break;
                    written += read;
                }
                return out;
            } catch (DataFormatException error) {
                throw new IllegalStateException(error);
            } finally {
                inflater.end();
            }
        }

        private int readVarInt() throws IOException {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                final int part = input.read();
                if (part < 0) throw new EOFException("the server closed the connection");
                midFrame = true;
                value |= (part & 0x7F) << shift;
                if ((part & 0x80) == 0) return value;
            }
            throw new IOException("var-int is too long");
        }

        /** Reads one frame and returns its decompressed body, id included. */
        private byte[] readFrame() throws IOException {
            final int length = readVarInt();
            final byte[] frame = input.readNBytes(length);
            if (frame.length != length) throw new EOFException("the server closed the connection");
            midFrame = false;
            if (compressionThreshold < 0) return frame;
            final Reader reader = new Reader(frame);
            final int dataLength = reader.varInt();
            final byte[] rest = reader.raw(reader.remaining());
            return dataLength == 0 ? rest : inflate(rest, dataLength);
        }

        // -- login -----------------------------------------------------------------------

        void login() throws IOException {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout((int) (options.timeoutSeconds * 1000));
            socket.connect(new InetSocketAddress(options.host, options.port),
                    (int) (options.timeoutSeconds * 1000));
            input = new BufferedInputStream(socket.getInputStream(), 1 << 16);
            output = socket.getOutputStream();

            write("CLIENT_HANDSHAKE", "ClientHandshakePacket", new Writer()
                    .varInt(PROTOCOL_VERSION).string(options.host).u16(options.port).varInt(2).bytes());
            write("CLIENT_LOGIN", "ClientLoginStartPacket", new Writer()
                    .string(username).raw(offlineUuid(username)).bytes());

            while (true) {
                final byte[] body = readFrame();
                final Reader reader = new Reader(body);
                final String packet = serverName("SERVER_LOGIN", reader.varInt());
                if (packet.equals("SetCompressionPacket")) {
                    compressionThreshold = reader.varInt();
                } else if (packet.equals("EncryptionRequestPacket")) {
                    encrypt(reader);
                } else if (packet.equals("LoginSuccessPacket")) {
                    write("CLIENT_LOGIN", "ClientLoginAcknowledgedPacket", new byte[0]);
                    break;
                } else if (packet.equals("LoginDisconnectPacket")) {
                    // A JSON text in the login state; the reason key is its translate member
                    final Matcher key = Pattern.compile("\"translate\"\\s*:\\s*\"([^\"]+)\"").matcher(reader.string());
                    throw new IOException("login refused: " + (key.find() ? key.group(1) : packet));
                }
            }

            // REQ-M2-008: a gameplay mode server gives a connection three seconds to say it has
            // the mod, and kicks it when it does not. Core mode asks for no such thing, so this
            // stays off by default: the no handshake path is a load shape of its own (TC-GAME-060).
            if (options.hello)
                write("CLIENT_CONFIGURATION", "ClientPluginMessagePacket", new Writer()
                        .string(HANDSHAKE_CHANNEL)
                        .varInt(C2S_HELLO).varInt(MOD_VERSION).varInt(schemaVersion).bytes());

            while (true) {
                final byte[] body = readFrame();
                final Reader reader = new Reader(body);
                final String packet = serverName("SERVER_CONFIGURATION", reader.varInt());
                switch (packet) {
                    case "SelectKnownPacksPacket" -> {
                        final int count = reader.varInt();
                        final Writer answer = new Writer().varInt(count);
                        for (int entry = 0; entry < count; entry++)
                            answer.string(reader.string()).string(reader.string()).string(reader.string());
                        write("CLIENT_CONFIGURATION", "ClientSelectKnownPacksPacket", answer.bytes());
                    }
                    case "KeepAlivePacket" -> write("CLIENT_CONFIGURATION", "ClientKeepAlivePacket",
                            new Writer().i64(reader.i64()).bytes());
                    case "FinishConfigurationPacket" -> {
                        write("CLIENT_CONFIGURATION", "ClientFinishConfigurationPacket", new byte[0]);
                        return;
                    }
                    default -> {
                        // registry data and the rest of the configuration stream, nothing to answer
                    }
                }
            }
        }

        /**
         * Answers an encryption request the way a vanilla client does and switches both directions
         * of the socket to AES/CFB8. The server sends nothing between the request and its answer to
         * the key, so no plaintext byte is left behind in the buffered input.
         */
        private void encrypt(Reader request) throws IOException {
            if (options.auth.equals("offline"))
                throw new IOException("the server asked for encryption: pass --auth offline-encrypted or --auth online, "
                        + "or start it with -Dsynaxis.app.auth=offline");
            request.string(); // server id, always empty
            final byte[] publicKey = request.raw(request.varInt());
            final byte[] challenge = request.raw(request.varInt());
            final boolean shouldAuthenticate = request.u8() != 0;
            if (shouldAuthenticate != options.auth.equals("online"))
                throw new IOException("--auth " + options.auth + " but the server "
                        + (shouldAuthenticate ? "verifies logins with a session server" : "does not verify logins"));
            final byte[] secret = new byte[16];
            new SecureRandom().nextBytes(secret);
            try {
                final PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKey));
                final Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                rsa.init(Cipher.ENCRYPT_MODE, key);
                final byte[] encryptedSecret = rsa.doFinal(secret);
                final byte[] encryptedChallenge = rsa.doFinal(challenge);
                write("CLIENT_LOGIN", "ClientEncryptionResponsePacket", new Writer()
                        .varInt(encryptedSecret.length).raw(encryptedSecret)
                        .varInt(encryptedChallenge.length).raw(encryptedChallenge).bytes());
                final SecretKeySpec aes = new SecretKeySpec(secret, "AES");
                final Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
                decrypt.init(Cipher.DECRYPT_MODE, aes, new IvParameterSpec(secret));
                final Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
                encrypt.init(Cipher.ENCRYPT_MODE, aes, new IvParameterSpec(secret));
                synchronized (writeLock) {
                    input = new BufferedInputStream(new CipherInputStream(socket.getInputStream(), decrypt), 1 << 16);
                    output = new CipherOutputStream(socket.getOutputStream(), encrypt);
                }
            } catch (GeneralSecurityException error) {
                throw new IOException("cannot answer the encryption request", error);
            }
        }

        // -- play ------------------------------------------------------------------------

        /** Reads and answers until the run ends; runs on the connection's own thread. */
        void readLoop(AtomicBoolean running) {
            try {
                socket.setSoTimeout(1000);
                while (running.get() && !closed) {
                    final byte[] body;
                    try {
                        body = readFrame();
                    } catch (SocketTimeoutException timeout) {
                        // A timeout that lands in the middle of a frame leaves the stream out of
                        // step: every later parse reads garbage and the connection would be counted
                        // lost for a reason that is ours, not the server's. Only an idle socket is
                        // safe to keep reading from.
                        if (midFrame) throw timeout;
                        continue;
                    }
                    final Reader reader = new Reader(body);
                    final String packet = serverName("SERVER_PLAY", reader.varInt());
                    switch (packet) {
                        case "KeepAlivePacket" -> {
                            write("CLIENT_PLAY", "ClientKeepAlivePacket", new Writer().i64(reader.i64()).bytes());
                            metrics.keepAlives.increment();
                        }
                        case "PlayerPositionAndLookPacket" -> {
                            final int teleportId = reader.varInt();
                            at = new double[]{reader.f64(), reader.f64(), reader.f64()};
                            write("CLIENT_PLAY", "ClientTeleportConfirmPacket",
                                    new Writer().varInt(teleportId).bytes());
                            metrics.teleportConfirms.increment();
                        }
                        case "PluginMessagePacket" -> {
                            final String channel = reader.string();
                            final byte[] payload = reader.raw(reader.remaining());
                            final long pending = pendingEchoNanos;
                            if (channel.equals(ECHO_CHANNEL) && pending >= 0 && payload.length == 8) {
                                metrics.roundTripsMillis.add((System.nanoTime() - pending) / 1_000_000.0);
                                pendingEchoNanos = -1;
                            }
                        }
                        case "DisconnectPacket" -> {
                            close("disconnect: " + disconnectReason(reader));
                            return;
                        }
                        default -> {
                            // every other play packet is read and dropped, the socket must stay drained
                        }
                    }
                }
            } catch (IOException | RuntimeException error) {
                // A decoding failure (a truncated frame, a bad inflate) is a RuntimeException. Letting
                // it kill this thread would leave the connection counted as alive and still sending,
                // which is the one direction a measuring tool must never be wrong in.
                close(error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }

        /** Sends position packets at the target rate; runs on the connection's own thread. */
        void sendLoop(AtomicBoolean running, long startNanos, long periodNanos, long echoPeriodNanos) {
            long moves = 0;
            long nextEcho = echoPeriodNanos > 0 ? startNanos + echoPeriodNanos : Long.MAX_VALUE;
            try {
                while (running.get() && !closed) {
                    final long deadline = startNanos + ++moves * periodNanos;
                    final long wait = deadline - System.nanoTime();
                    if (wait > 0) LockSupport.parkNanos(wait);
                    if (!running.get() || closed) return;
                    final double angle = phase + (System.nanoTime() - startNanos) / 1e9 * WALK_SPEED / radius;
                    final double[] next = toward(at, 0.5 + radius * Math.cos(angle), FLOOR_Y, 0.5 + radius * Math.sin(angle));
                    at = next;
                    write("CLIENT_PLAY", "ClientPlayerPositionAndRotationPacket", new Writer()
                            .f64(next[0])
                            .f64(next[1])
                            .f64(next[2])
                            .f32((float) (Math.toDegrees(angle) % 360.0 - 180.0))
                            .f32(0.0f)
                            .u8(1)
                            .bytes());
                    final long now = System.nanoTime();
                    if (now >= nextEcho) {
                        nextEcho = now + echoPeriodNanos;
                        pendingEchoNanos = now;
                        write("CLIENT_PLAY", "ClientPluginMessagePacket",
                                new Writer().string(ECHO_CHANNEL).f64(now / 1e9).bytes());
                        metrics.echoesSent.increment();
                    }
                }
            } catch (IOException error) {
                close(error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }

        /** The point on the way from {@code from} to (x, y, z) at most {@link #STEP_LIMIT} blocks from it. */
        private static double[] toward(double[] from, double x, double y, double z) {
            final double dx = x - from[0], dy = y - from[1], dz = z - from[2];
            final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= STEP_LIMIT) return new double[]{x, y, z};
            final double scale = STEP_LIMIT / distance;
            return new double[]{from[0] + dx * scale, from[1] + dy * scale, from[2] + dz * scale};
        }

        private String disconnectReason(Reader reader) {
            try {
                return componentReason(reader);
            } catch (RuntimeException error) {
                return "unreadable";
            }
        }

        synchronized void close(String reason) {
            if (closed) return;
            closed = true;
            if (reason != null) {
                metrics.lost.increment();
                metrics.losses.add(username + ": " + reason);
                if (reason.startsWith("disconnect: "))
                    metrics.disconnects.merge(reason.substring("disconnect: ".length()), 1L, Long::sum);
            }
            metrics.connected.decrementAndGet();
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing a socket that is already broken is the normal path here
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Options
    // ---------------------------------------------------------------------------------------

    private static final class Options {
        String host = "127.0.0.1";
        int port = 25565;
        int connections = 50;
        double hz = 100.0;
        double seconds = 330.0;
        double echoHz = 0.2;
        boolean hello = false;
        String auth = "offline";
        double timeoutSeconds = 30.0;
        double sampleSeconds = 10.0;
        Path out;
        Path repository = Path.of(System.getProperty("user.dir"));
    }

    private static Options parse(String[] arguments) {
        final Options options = new Options();
        for (int index = 0; index < arguments.length; index += 2) {
            final String flag = arguments[index];
            if (index + 1 >= arguments.length) throw new IllegalArgumentException(flag + " needs a value");
            final String value = arguments[index + 1];
            switch (flag) {
                case "--host" -> options.host = value;
                case "--port" -> options.port = Integer.parseInt(value);
                case "--connections" -> options.connections = Integer.parseInt(value);
                case "--hz" -> options.hz = Double.parseDouble(value);
                case "--seconds" -> options.seconds = Double.parseDouble(value);
                case "--echo-hz" -> options.echoHz = Double.parseDouble(value);
                case "--hello" -> options.hello = Boolean.parseBoolean(value);
                case "--auth" -> {
                    if (!List.of("offline", "offline-encrypted", "online").contains(value))
                        throw new IllegalArgumentException("--auth is offline, offline-encrypted or online");
                    options.auth = value;
                }
                case "--sample-seconds" -> options.sampleSeconds = Double.parseDouble(value);
                case "--out" -> options.out = Path.of(value);
                case "--repo" -> options.repository = Path.of(value);
                default -> throw new IllegalArgumentException("unknown option " + flag);
            }
        }
        return options;
    }

    // ---------------------------------------------------------------------------------------
    // Run
    // ---------------------------------------------------------------------------------------

    public static void main(String[] arguments) throws Exception {
        final Options options = parse(arguments);
        loadRegistries(options.repository);

        final Metrics metrics = new Metrics();
        final List<Connection> connections = new ArrayList<>();
        final CountDownLatch loggedIn = new CountDownLatch(options.connections);
        final List<Thread> threads = new ArrayList<>();
        final AtomicBoolean running = new AtomicBoolean(true);
        final long loginStart = System.nanoTime();

        for (int index = 0; index < options.connections; index++) {
            final Connection connection = new Connection(options, metrics, index);
            connections.add(connection);
            final Thread thread = new Thread(() -> {
                try {
                    connection.login();
                    metrics.connected.incrementAndGet();
                } catch (IOException | RuntimeException error) {
                    System.out.println("login failed " + connection.username + ": " + error.getMessage());
                    connection.closed = true;
                } finally {
                    loggedIn.countDown();
                }
                if (!connection.closed) connection.readLoop(running);
            }, "flood-read-" + index);
            thread.start();
            threads.add(thread);
        }
        if (!loggedIn.await(120, TimeUnit.SECONDS)) throw new IllegalStateException("logins did not finish");
        final double loginSeconds = (System.nanoTime() - loginStart) / 1e9;
        final long logins = metrics.connected.get();
        System.out.printf(Locale.ROOT, "flood: %d/%d logged in in %.1f s, sending %.0f packets/s per "
                        + "connection for %.0f s%n",
                logins, options.connections, loginSeconds, options.hz, options.seconds);

        // Only the packets sent inside the measured window count, so the ramp up cannot inflate
        // or deflate the rate the report quotes.
        final long sentBeforeWindow = metrics.sent.sum();
        final long windowStart = System.nanoTime();
        final long period = (long) (1e9 / options.hz);
        final long echoPeriod = options.echoHz > 0 ? (long) (1e9 / options.echoHz) : 0;
        for (Connection connection : connections) {
            if (connection.closed) continue;
            final Thread thread = new Thread(() -> connection.sendLoop(running, windowStart, period, echoPeriod),
                    "flood-send-" + connection.index);
            thread.start();
            threads.add(thread);
        }

        final List<Map<String, Object>> samples = new ArrayList<>();
        long previousSent = sentBeforeWindow;
        long previousNanos = windowStart;
        final long endNanos = windowStart + (long) (options.seconds * 1e9);
        final long sampleNanos = (long) (options.sampleSeconds * 1e9);
        while (true) {
            // Cut the last sleep at the window end, otherwise the measured window is rounded up to
            // a multiple of --sample-seconds and the report quotes a duration that was never asked
            // for. A leftover shorter than half a sample is not worth a rate line of its own.
            final long remaining = endNanos - System.nanoTime();
            if (remaining <= 0) break;
            Thread.sleep(Duration.ofNanos(Math.min(sampleNanos, remaining)));
            if (remaining < sampleNanos / 2) break;
            final long now = System.nanoTime();
            final long sent = metrics.sent.sum();
            final double rate = (sent - previousSent) / ((now - previousNanos) / 1e9);
            final Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("t", round((now - windowStart) / 1e9, 1));
            sample.put("connected", metrics.connected.get());
            sample.put("packets_per_second", round(rate, 1));
            samples.add(sample);
            System.out.printf(Locale.ROOT, "t=%6.1fs connected=%2d rate=%8.1f pkt/s total=%d%n",
                    (now - windowStart) / 1e9, metrics.connected.get(), rate, sent - sentBeforeWindow);
            previousSent = sent;
            previousNanos = now;
        }

        running.set(false);
        final double elapsed = (System.nanoTime() - windowStart) / 1e9;
        final long windowSent = metrics.sent.sum() - sentBeforeWindow;
        final long connectionsAtEnd = metrics.connected.get();
        for (Thread thread : threads) thread.join(30_000);
        for (Connection connection : connections) connection.close(null);

        final Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("connections_requested", options.connections);
        summary.put("connections_logged_in", logins);
        summary.put("login_seconds", round(loginSeconds, 1));
        summary.put("target_hz_per_connection", options.hz);
        summary.put("target_packets_per_second", round(options.hz * logins, 1));
        summary.put("window_seconds", round(elapsed, 1));
        summary.put("packets_sent_in_window", windowSent);
        summary.put("achieved_packets_per_second", round(windowSent / elapsed, 1));
        summary.put("connections_at_end", connectionsAtEnd);
        summary.put("connections_lost", metrics.lost.sum());
        summary.put("loss_reasons", new ArrayList<>(metrics.losses));
        summary.put("disconnect_reasons", new LinkedHashMap<>(metrics.disconnects));
        summary.put("keep_alives_answered", metrics.keepAlives.sum());
        summary.put("teleport_confirms", metrics.teleportConfirms.sum());
        summary.put("echoes_sent", metrics.echoesSent.sum());
        summary.put("echo_rtt_ms", distribution(metrics.roundTripsMillis));
        summary.put("samples", samples);

        System.out.printf(Locale.ROOT,
                "flood: achieved %.1f packets/s over %.1f s (%d packets, %d connections, %d lost)%n",
                windowSent / elapsed, elapsed, windowSent, logins, metrics.lost.sum());
        if (options.out != null) {
            Files.createDirectories(options.out);
            Files.writeString(options.out.resolve("flood.json"), json(summary));
            System.out.println("flood: wrote " + options.out.resolve("flood.json"));
        } else {
            System.out.println(json(summary));
        }
    }

    private static double round(double value, int digits) {
        final double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }

    private static Map<String, Object> distribution(List<Double> values) {
        final Map<String, Object> result = new LinkedHashMap<>();
        final List<Double> sorted;
        synchronized (values) {
            sorted = new ArrayList<>(values);
        }
        Collections.sort(sorted);
        result.put("count", sorted.size());
        if (sorted.isEmpty()) return result;
        result.put("p50", round(sorted.get(sorted.size() / 2), 2));
        result.put("p99", round(sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.99))), 2));
        result.put("max", round(sorted.get(sorted.size() - 1), 2));
        return result;
    }

    /** A minimal json writer, enough for the shapes this summary uses. */
    private static String json(Object value) {
        final StringBuilder builder = new StringBuilder();
        writeJson(value, builder);
        return builder.toString();
    }

    private static void writeJson(Object value, StringBuilder builder) {
        switch (value) {
            case null -> builder.append("null");
            case Map<?, ?> map -> {
                builder.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) builder.append(',');
                    first = false;
                    writeJson(String.valueOf(entry.getKey()), builder);
                    builder.append(':');
                    writeJson(entry.getValue(), builder);
                }
                builder.append('}');
            }
            case List<?> list -> {
                builder.append('[');
                for (int index = 0; index < list.size(); index++) {
                    if (index > 0) builder.append(',');
                    writeJson(list.get(index), builder);
                }
                builder.append(']');
            }
            case Number number -> builder.append(number);
            case String text -> {
                builder.append('"');
                for (int index = 0; index < text.length(); index++) {
                    final char character = text.charAt(index);
                    if (character == '"' || character == '\\') builder.append('\\').append(character);
                    else if (character < 0x20) builder.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    else builder.append(character);
                }
                builder.append('"');
            }
            default -> writeJson(String.valueOf(value), builder);
        }
    }
}
