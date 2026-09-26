package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientKeepAlivePacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket.Intent;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginAcknowledgedPacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.KeepAlivePacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.FinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.UpdateEnabledFeaturesPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.SetCompressionPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.frame;
import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.freePort;
import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.id;
import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.readUntil;
import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.readVarInt;
import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.send;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code stop} in a JVM of its own while a client that was let in never finishes its configuration
 * ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-008}, §11.4 {@code gate_closed → saving}, §28.2):
 * the client never answers the known packs, or never acknowledges the end of the configuration.
 * Nothing on the server times either out within the save budget, so without the disconnect of the
 * save phase the shutdown save waited its full 30 s for a snapshot that never came. The client
 * answers keep-alives like a live one: a silent client would be kicked for those after 15 s.
 */
public class ShutdownBeforeTheWorldLaunchTest {

    /** Well under the save budget of 30 s; a stop without anybody to wait for takes about half a second. */
    private static final long STOP_WITHIN_SECONDS = 10;

    @Test
    public void aClientThatNeverAnswersTheKnownPacksDoesNotHoldTheStop(@TempDir Path directory) throws Exception {
        stopWhileAClientIsStuck(directory, false);
    }

    @Test
    public void aClientThatNeverAcknowledgesTheEndOfItsConfigurationDoesNotHoldTheStop(@TempDir Path directory)
            throws Exception {
        stopWhileAClientIsStuck(directory, true);
    }

    private static void stopWhileAClientIsStuck(Path directory, boolean answerKnownPacks) throws Exception {
        final int port = freePort();
        try (ConfigFileLaunchTest.Launched server = ConfigFileLaunchTest.Launched.start(directory, Main.class,
                "-Dsynaxis.app.log=stdout", "-Dsynaxis.app.auth=offline", "-Dsynaxis.app.core-mode=on",
                "-Dsynaxis.app.port=" + port, "-Dsynaxis.app.metrics.bind=127.0.0.1:0");
             SocketChannel client = SocketChannel.open()) {
            server.awaitLine("synaxis server-app listening on");
            client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            client.socket().setSoTimeout(30_000);
            final InputStream in = client.socket().getInputStream();
            send(client, ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION,
                    "localhost", port, Intent.LOGIN), 0);
            send(client, ConnectionState.LOGIN, new ClientLoginStartPacket("Stuck", UUID.randomUUID()), 0);
            final InputStream setCompression = new ByteArrayInputStream(frame(in));
            assertEquals(id(ConnectionState.LOGIN, SetCompressionPacket.class), readVarInt(setCompression));
            final int threshold = readVarInt(setCompression);
            final List<ConfigFileLaunchTest.Frame> frames = new ArrayList<>();
            readUntil(in, frames, id(ConnectionState.LOGIN, LoginSuccessPacket.class));
            send(client, ConnectionState.LOGIN, new ClientLoginAcknowledgedPacket(), threshold);
            // Sent once the configuration event is over: the player is loaded and let in
            readUntil(in, frames, id(ConnectionState.CONFIGURATION, UpdateEnabledFeaturesPacket.class));
            if (answerKnownPacks) {
                send(client, ConnectionState.CONFIGURATION, new ClientSelectKnownPacksPacket(List.of()), threshold);
                readUntil(in, frames, id(ConnectionState.CONFIGURATION, FinishConfigurationPacket.class));
            }

            // From here on the client only answers keep-alives, and stays connected
            client.socket().setSoTimeout(0);
            final int keepAlive = id(ConnectionState.CONFIGURATION, KeepAlivePacket.class);
            Thread.ofVirtual().start(() -> answerKeepAlives(client, in, keepAlive, threshold));
            final long start = System.nanoTime();
            final OutputStream console = server.process().getOutputStream();
            console.write("stop\n".getBytes(StandardCharsets.UTF_8));
            console.flush();
            final boolean exited = server.process().waitFor(STOP_WITHIN_SECONDS, TimeUnit.SECONDS);
            final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            server.reader().join(TimeUnit.SECONDS.toMillis(10));
            final String log = server.log();

            assertTrue(exited, "stop took over " + STOP_WITHIN_SECONDS + " s:\n" + log);
            assertEquals(0, server.process().exitValue(), log);
            assertTrue(log.contains("reason=synaxis:server-stopping"), "the stuck client is disconnected:\n" + log);
            assertFalse(log.contains("The shutdown save did not complete within its budget"), log);
            assertFalse(log.contains("Not saved on shutdown"), log);
            final String saved = ledgerLine(directory, "\"shutdown_saved\"");
            assertTrue(saved.contains("\"online\":1") && saved.contains("\"saved\":1") && saved.contains("\"failed\":0")
                    && saved.contains("\"timed_out\":\"false\""), saved + " (stop took " + tookMillis + " ms)");
        }
    }

    private static void answerKeepAlives(SocketChannel client, InputStream in, int keepAlive, int threshold) {
        try {
            while (true) {
                final DataInputStream packet = new DataInputStream(new ByteArrayInputStream(frame(in)));
                // A compressed frame is no keep-alive, which is a few bytes
                if (readVarInt(packet) != 0 || readVarInt(packet) != keepAlive) continue;
                send(client, ConnectionState.CONFIGURATION, new ClientKeepAlivePacket(packet.readLong()), threshold);
            }
        } catch (IOException _) {
            // The server closed the connection, or the test did
        }
    }

    private static String ledgerLine(Path directory, String event) throws Exception {
        try (Stream<Path> files = Files.list(directory.resolve("logs"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith("events-")).toList()) {
                for (String line : Files.readAllLines(file)) if (line.contains(event)) return line;
            }
        }
        throw new AssertionError("no " + event + " in the ledger");
    }
}
