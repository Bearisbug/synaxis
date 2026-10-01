package io.github.bearisbug.synaxis.app.management;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.JsonArray;
import io.github.bearisbug.synaxis.app.command.HostCommands;
import io.github.bearisbug.synaxis.app.command.Profiler;
import io.github.bearisbug.synaxis.app.moderation.HostModeration;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.github.bearisbug.synaxis.app.management.ManagementSettingsTest.TOKEN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the management port does with peers that will never call a method
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-022} the auth and the size items, §15 the management
 * row, §19 the scenarios of a browser's upgrade request and of unfinished handshakes): an upgrade
 * request carrying {@code Origin} is refused even with the right token, because a browser always
 * sends one and a reverse proxy may be the one adding the token; and with
 * {@value #UNFINISHED_CAP} connections whose handshake is not over, the next one is closed as soon
 * as it is accepted instead of holding a thread and a socket until its deadline.
 */
@EnvTest
class ManagementPortGuardsIntegrationTest {

    /** The handshakes left unfinished at which a new connection is closed (§15). */
    private static final int UNFINISHED_CAP = 64;
    /** Far past the test, so that only the cap can close a connection here. */
    private static final Duration LONG_DEADLINE = Duration.ofSeconds(30);

    @TempDir
    Path workingDirectory;

    private final List<AutoCloseable> opened = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        for (AutoCloseable closeable : opened.reversed()) closeable.close();
    }

    private InetSocketAddress serve(Env env, Duration handshakeDeadline) {
        final PlayerStore store = PlayerStore.inMemory(env.process(), (_, _) -> { });
        final Permissions none = new Permissions() {
            @Override
            public boolean has(Player player, String node) {
                return false;
            }

            @Override
            public Set<String> roles(Player player) {
                return Set.of();
            }
        };
        final HostCommands commands = new HostCommands(env.process(), none, store, (_, _) -> { },
                new Profiler(workingDirectory.resolve("profiles"), "test", Clock.systemUTC()));
        final ManagementMethods methods = new ManagementMethods(env.process(), commands, store,
                HostModeration.withoutDatabase(env.process()), null, null, "test", (_, _) -> { });
        final ManagementServer server = ManagementServer.serve(ManagementSettings.parse("true", "127.0.0.1:0", TOKEN),
                env.process(), methods, handshakeDeadline);
        opened.add(server);
        return Objects.requireNonNull(server.address());
    }

    @Test
    void anUpgradeRequestWithAnOriginIsRefusedEvenWithTheToken(Env env) throws Exception {
        final InetSocketAddress address = serve(env, ManagementServer.HANDSHAKE_DEADLINE);
        final byte[] refused = rawRequest(address, upgrade(address, null, null));
        final String refusal = new String(refused, StandardCharsets.US_ASCII);
        assertTrue(refusal.startsWith("HTTP/1.1 404 WebSocket Upgrade Failure\r\n"), refusal);

        // What a page's new WebSocket(...) sends through a proxy that adds the token: the answer of a
        // request without any token, byte for byte; a sandboxed page sends the origin "null"
        for (String origin : List.of("Origin: https://evil.example", "origin: null"))
            assertArrayEquals(refused, rawRequest(address, upgrade(address, "Bearer " + TOKEN, origin)), origin);

        // The same request without the header is upgraded; the close frame after it ends the exchange
        final String accepted = new String(rawRequest(address, upgrade(address, "Bearer " + TOKEN, null)
                + "\u0088\u0080\0\0\0\0"), StandardCharsets.ISO_8859_1);
        assertTrue(accepted.startsWith("HTTP/1.1 101"), accepted);
    }

    @Test
    void pastTheCapOfUnfinishedHandshakesANewConnectionIsClosedAtOnce(Env env) throws Exception {
        final InetSocketAddress address = serve(env, LONG_DEADLINE);
        final List<Socket> silent = new ArrayList<>();
        try {
            for (int i = 0; i < UNFINISHED_CAP; i++) silent.add(open(address));
            final List<ILoggingEvent> lines = new ArrayList<>();
            final long tookMillis = capture(lines, () -> {
                try (Socket past = open(address)) {
                    final long start = System.nanoTime();
                    assertClosedWithoutAnswer(past.getInputStream());
                    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                }
            });
            assertTrue(tookMillis < 1_000, "closed as it is accepted, not at a deadline: " + tookMillis + " ms");
            assertEquals(1, lines.stream().filter(line -> line.getLevel() == Level.WARN && line.getFormattedMessage()
                    .startsWith("Management connection closed at once: ")).count(), lines.toString());

            // The ones counted are left to their own deadline
            final Socket first = silent.getFirst();
            first.setSoTimeout(200);
            assertThrows(SocketTimeoutException.class, () -> first.getInputStream().read());
        } finally {
            for (Socket socket : silent) socket.close();
        }
        // Once the server has seen them closed they count no more, and a client with the token gets in
        final ManagementClient client = connectWithin(address, Duration.ofSeconds(10));
        assertEquals(new JsonArray(), client.call(1, "minecraft:players", null).get("result"));
    }

    private interface Body<T> {
        T run() throws Exception;
    }

    private static <T> T capture(List<ILoggingEvent> lines, Body<T> body) throws Exception {
        final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            return body.run();
        } finally {
            root.detachAppender(appender);
            appender.stop();
            lines.addAll(appender.list);
        }
    }

    private ManagementClient connectWithin(InetSocketAddress address, Duration within) throws Exception {
        final long deadline = System.nanoTime() + within.toNanos();
        while (true) {
            try {
                final ManagementClient client = ManagementClient.connect(address, "Bearer " + TOKEN);
                opened.add(client);
                return client;
            } catch (Exception e) {
                if (System.nanoTime() > deadline) throw new AssertionError("no connection within " + within, e);
                Thread.sleep(100);
            }
        }
    }

    private static Socket open(InetSocketAddress address) throws IOException {
        final Socket socket = new Socket(Proxy.NO_PROXY);
        socket.connect(address, 5_000);
        socket.setSoTimeout(2_000);
        return socket;
    }

    private static void assertClosedWithoutAnswer(InputStream in) throws IOException {
        final ByteArrayOutputStream answered = new ByteArrayOutputStream();
        try {
            for (int b; (b = in.read()) >= 0; ) answered.write(b);
        } catch (SocketTimeoutException e) {
            throw new AssertionError("still open after 2 s", e);
        } catch (SocketException _) {
            // Reset: closed all the same
        }
        assertEquals(0, answered.size(), answered.toString(StandardCharsets.US_ASCII));
    }

    /** What the server answers a raw HTTP request, read until it closes the socket or 2 s pass. */
    private static byte[] rawRequest(InetSocketAddress address, String request) throws IOException {
        try (Socket socket = open(address)) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            final ByteArrayOutputStream answer = new ByteArrayOutputStream();
            try {
                socket.getInputStream().transferTo(answer);
            } catch (SocketTimeoutException _) {
                // Still open: what came so far is the whole answer
            }
            return answer.toByteArray();
        }
    }

    private static String upgrade(InetSocketAddress address, @Nullable String authorization, @Nullable String origin) {
        return "GET / HTTP/1.1\r\nHost: " + address.getHostString() + ":" + address.getPort() + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n" + (authorization == null ? "" : "Authorization: " + authorization + "\r\n")
                + (origin == null ? "" : origin + "\r\n") + "\r\n";
    }
}
