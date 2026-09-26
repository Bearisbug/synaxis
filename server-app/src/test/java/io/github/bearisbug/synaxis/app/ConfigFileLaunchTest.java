package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket.Intent;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginAcknowledgedPacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.FinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.SelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.SetCompressionPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code synaxis.properties} in a JVM of its own ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-033},
 * {@code REQ-PROD-038}, {@code API-PROD-023}, {@code API-PROD-020}). Three settings are fixed the
 * first time they are read — where the log goes as the logging is set up, {@code JvmFlags} as it is
 * loaded, {@code ServerConfig} as the process is created — and each takes the file's value, while
 * the command line still wins over the file. The compression the file names reaches the wire.
 * <p>
 * The JVM runs the production {@code logback.xml}: on the test classpath logback would otherwise
 * prefer {@code logback-test.xml}, which never reads {@code synaxis.app.log}.
 */
public class ConfigFileLaunchTest {

    private static final int THRESHOLD = 512;
    private static final int LEVEL = 3;
    /** RFC 1950: the second header byte of a zlib stream at levels 2 to 5. */
    private static final int LEVEL_TWO_TO_FIVE = 0x5E;

    @Test
    public void aShardTakesTheFileOverItsDefaultsAndTheCommandLineOverTheFile(@TempDir Path directory) throws Exception {
        final int port = freePort();
        Files.writeString(directory.resolve(ConfigFile.DEFAULT_FILE), String.join("\n",
                "# docs/TEST.md TC-PROD-442",
                "synaxis.app.log=stdout",
                "synaxis.app.auth=offline",
                "synaxis.app.core-mode=on",
                "synaxis.app.port=" + port,
                "synaxis.app.metrics.bind=127.0.0.1:0",
                "synaxis.max-players=300",
                "synaxis.chunk-view-distance=10",
                "synaxis.entity-view-distance=6",
                "synaxis.dispatcher-threads=3",
                "synaxis.compression-threshold=" + THRESHOLD,
                "synaxis.compression-level=" + LEVEL,
                ""));
        try (Launched shard = Launched.start(directory, ShardMain.class, "-Dsynaxis.max-players=200")) {
            final String log = shard.awaitLine("synaxis server-app listening on");

            assertTrue(log.contains("Effective config file: path=" + directory.toRealPath().resolve(ConfigFile.DEFAULT_FILE)
                    + " keys=11 overridden_by_command_line=[synaxis.max-players]"), log);
            // The command line over the file
            assertTrue(log.contains("maxPlayers=200,"), log);
            // The file over ShardMain's defaults (12 and cores - 2) and Main's (8); grid is still ShardMain's
            assertTrue(log.contains("chunkViewDistance=10,"), log);
            assertTrue(log.contains("dispatcherThreads=3,"), log);
            assertTrue(log.contains("entityViewDistance=6,"), log);
            assertTrue(log.contains("threadProvider=GRID,"), log);
            assertTrue(log.contains("compressionThreshold=" + THRESHOLD + ","), log);
            // JvmFlags, read once as the engine loads it
            assertTrue(log.contains("compressionLevel=" + LEVEL + "]"), log);
            // The logging setup, which reads synaxis.app.log as Main's logger is created
            assertFalse(Files.exists(directory.resolve("logs").resolve("latest.log")),
                    "synaxis.app.log=stdout reached the logging setup, so it built no file appender");

            final List<Frame> frames = loginAndConfigure(port);
            int compressed = 0;
            for (Frame frame : frames) {
                if (frame.dataLength() == 0) {
                    assertTrue(frame.packet().length < THRESHOLD, "an uncompressed body is below the threshold: "
                            + frame.packet().length);
                } else {
                    compressed++;
                    assertTrue(frame.dataLength() >= THRESHOLD, "a compressed body is at least the threshold: "
                            + frame.dataLength());
                    assertEquals(LEVEL_TWO_TO_FIVE, frame.compressed()[1] & 0xFF, "the level bits of the zlib header");
                    assertArrayEquals(deflate(frame.packet(), LEVEL), frame.compressed(), "deflated at level 3");
                }
            }
            assertTrue(compressed > 0, "the registries are sent compressed: " + frames.size() + " frames");
            assertTrue(compressed < frames.size(), "the login success is sent uncompressed");
        }
    }

    @Test
    public void anUnknownKeyRefusesBothLaunchersBeforeAnythingIsBound(@TempDir Path directory) throws Exception {
        final Path named = directory.resolve("shard.properties");
        Files.writeString(named, "synaxis.motd=Synaxis\nsynaxis.max-player=1\n");
        Files.writeString(directory.resolve(ConfigFile.DEFAULT_FILE), "synaxis.max-player=1\n");
        // On the command line, so that a launcher that let the file through would come up on a free port
        final String[] launch = {"-Dsynaxis.app.log=stdout", "-Dsynaxis.app.auth=offline", "-Dsynaxis.app.core-mode=on",
                "-Dsynaxis.app.port=" + freePort(), "-Dsynaxis.app.metrics.bind=127.0.0.1:0"};

        try (Launched main = Launched.start(directory, Main.class, concat(launch, "-D" + ConfigFile.PATH_PROPERTY + "=" + named))) {
            assertRefused(main, named + ": unknown key synaxis.max-player (nearest known key synaxis.max-players)");
        }
        try (Launched shard = Launched.start(directory, ShardMain.class, launch)) {
            assertRefused(shard, directory.toRealPath().resolve(ConfigFile.DEFAULT_FILE)
                    + ": unknown key synaxis.max-player (nearest known key synaxis.max-players)");
        }
    }

    private static String[] concat(String[] first, String last) {
        final String[] all = Arrays.copyOf(first, first.length + 1);
        all[first.length] = last;
        return all;
    }

    private static void assertRefused(Launched launched, String refusal) throws InterruptedException {
        assertEquals(1, launched.awaitExit());
        final String log = launched.log();
        assertTrue(log.contains("Refusing to start: " + refusal), log);
        assertFalse(log.contains("Effective"), "refused before the process is created: " + log);
        assertFalse(log.contains("listening on"), log);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /**
     * Logs in and goes through the configuration, answering the known packs with none so that the
     * registries are sent in full.
     *
     * @return every frame after SetCompression up to and with FinishConfiguration
     */
    private static List<Frame> loginAndConfigure(int port) throws IOException, DataFormatException {
        try (SocketChannel channel = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), port))) {
            channel.socket().setSoTimeout(30_000);
            final InputStream in = channel.socket().getInputStream();
            send(channel, ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION,
                    "localhost", port, Intent.LOGIN), 0);
            send(channel, ConnectionState.LOGIN, new ClientLoginStartPacket("ConfigFile", UUID.randomUUID()), 0);

            final InputStream setCompression = new ByteArrayInputStream(frame(in));
            assertEquals(id(ConnectionState.LOGIN, SetCompressionPacket.class), readVarInt(setCompression));
            assertEquals(THRESHOLD, readVarInt(setCompression), "SetCompression announces the file's threshold");

            final List<Frame> frames = new ArrayList<>();
            readUntil(in, frames, id(ConnectionState.LOGIN, LoginSuccessPacket.class));
            send(channel, ConnectionState.LOGIN, new ClientLoginAcknowledgedPacket(), THRESHOLD);
            readUntil(in, frames, id(ConnectionState.CONFIGURATION, SelectKnownPacksPacket.class));
            send(channel, ConnectionState.CONFIGURATION, new ClientSelectKnownPacksPacket(List.of()), THRESHOLD);
            readUntil(in, frames, id(ConnectionState.CONFIGURATION, FinishConfigurationPacket.class));
            return frames;
        }
    }

    private static int id(ConnectionState state, Class<?> packet) {
        return PacketVanilla.SERVER_PACKET_PARSER.stateRegistry(state).packetInfo(packet).id();
    }

    private static void send(SocketChannel channel, ConnectionState state, ClientPacket packet, int threshold)
            throws IOException {
        final NetworkBuffer framed = PacketWriting.allocateTrimmedPacket(state, packet, null, threshold);
        while (!framed.writeChannel(channel)) {
            // A blocking channel writes everything
        }
    }

    private static void readUntil(InputStream in, List<Frame> frames, int packetId) throws IOException, DataFormatException {
        Frame frame;
        do {
            frame = compressedFrame(in);
            frames.add(frame);
        } while (frame.packetId() != packetId);
    }

    /**
     * One frame once compression is on.
     *
     * @param dataLength the length of the packet before compression, 0 when it is not compressed
     * @param compressed the bytes after the data length
     * @param packet     the packet id and body
     */
    private record Frame(int dataLength, byte[] compressed, byte[] packet) {
        int packetId() throws IOException {
            return readVarInt(new ByteArrayInputStream(packet));
        }
    }

    private static Frame compressedFrame(InputStream in) throws IOException, DataFormatException {
        final ByteArrayInputStream rest = new ByteArrayInputStream(frame(in));
        final int dataLength = readVarInt(rest);
        final byte[] compressed = rest.readAllBytes();
        if (dataLength == 0) return new Frame(0, compressed, compressed);
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            final byte[] packet = new byte[dataLength];
            assertEquals(dataLength, inflater.inflate(packet));
            return new Frame(dataLength, compressed, packet);
        } finally {
            inflater.end();
        }
    }

    private static byte[] frame(InputStream in) throws IOException {
        final int length = readVarInt(in);
        final byte[] frame = in.readNBytes(length);
        if (frame.length < length) throw new EOFException("the server closed the connection");
        return frame;
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; ; shift += 7) {
            final int b;
            try {
                b = in.read();
            } catch (SocketTimeoutException e) {
                throw new AssertionError("the server sent nothing more", e);
            }
            if (b < 0) throw new EOFException("the server closed the connection");
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
    }

    private static byte[] deflate(byte[] input, int level) {
        final Deflater deflater = new Deflater(level);
        try {
            deflater.setInput(input);
            deflater.finish();
            final byte[] output = new byte[input.length + 64];
            final int length = deflater.deflate(output);
            return Arrays.copyOf(output, length);
        } finally {
            deflater.end();
        }
    }

    /** A server application in a JVM of its own, its output collected line by line. */
    private record Launched(Process process, List<String> output, Thread reader) implements AutoCloseable {

        static Launched start(Path directory, Class<?> launcher, String... properties) throws IOException {
            final List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx1g",
                    "-cp", System.getProperty("java.class.path"), "-Dlogback.configurationFile=logback.xml"));
            command.addAll(List.of(properties));
            command.add(launcher.getName());
            final Process process = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectErrorStream(true).start();
            final List<String> output = new CopyOnWriteArrayList<>();
            final Thread reader = Thread.ofPlatform().start(() -> {
                try (BufferedReader lines = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) output.add(line);
                } catch (IOException _) {
                    // the JVM is gone, what it wrote is in `output`
                }
            });
            return new Launched(process, output, reader);
        }

        String log() {
            return String.join("\n", output);
        }

        /** Waits for a line holding the text, then returns the whole log so far. */
        String awaitLine(String text) throws InterruptedException {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (output.stream().noneMatch(line -> line.contains(text))) {
                if (!process.isAlive() || System.nanoTime() > deadline)
                    throw new AssertionError("no line with \"" + text + "\":\n" + log());
                Thread.sleep(50);
            }
            return log();
        }

        int awaitExit() throws InterruptedException {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the JVM exits: " + log());
            reader.join(TimeUnit.SECONDS.toMillis(10));
            return process.exitValue();
        }

        /** Ends the JVM without a graceful stop, which this test is not about. */
        @Override
        public void close() {
            process.destroyForcibly();
            try {
                process.waitFor(30, TimeUnit.SECONDS);
                reader.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
