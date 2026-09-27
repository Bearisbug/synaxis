package io.github.bearisbug.synaxis.app.management;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.app.command.HostCommands;
import io.github.bearisbug.synaxis.app.command.Profiler;
import io.github.bearisbug.synaxis.app.moderation.HostModeration;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.bearisbug.synaxis.app.management.ManagementClient.await;
import static io.github.bearisbug.synaxis.app.management.ManagementSettingsTest.TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The outbound bound of a management connection holds however many replies are sent at once
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-022} the size item, §16 the row of replies sent at
 * once, §19 the scenario of replies sent at once). Each call is answered on a virtual thread of its
 * own; the ledger holds every call until all of them are in, then lets them all answer together to
 * a peer that reads nothing, so that many replies are judged against the bound at the same moment.
 */
@EnvTest
class ManagementBacklogRaceIntegrationTest {

    private static final Pattern CLOSED = Pattern.compile(
            "Management connection closed with 1008: remote=\\S+ has (\\d+) frames and (\\d+) bytes queued that it does not read .*");
    /** Far more replies than fit under the byte bound, even after the kernel took its share. */
    private static final int CALLS = 128;
    /** Rounds, each on a connection of its own: one round catches two replies judged at once most of the time, not always. */
    private static final int ROUNDS = 5;
    /** Echoed by every reply, which makes each one a frame of about 60 KB. */
    private static final String ID = "i".repeat(60_000);

    @TempDir
    Path workingDirectory;

    private final List<AutoCloseable> opened = new ArrayList<>();
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ManagementServer.class);

    @BeforeEach
    void captureTheLog() {
        lines.start();
        logger.addAppender(lines);
    }

    @AfterEach
    void closeEverything() throws Exception {
        for (AutoCloseable closeable : opened.reversed()) closeable.close();
        logger.detachAppender(lines);
        lines.stop();
    }

    private InetSocketAddress serve(Env env, BiConsumer<String, Map<String, ?>> ledger) {
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
        final PlayerStore store = PlayerStore.inMemory(env.process(), (_, _) -> { });
        final HostCommands commands = new HostCommands(env.process(), none, store, (_, _) -> { },
                new Profiler(workingDirectory.resolve("profiles"), "test", Clock.systemUTC()));
        final ManagementMethods methods = new ManagementMethods(env.process(), commands, store,
                HostModeration.withoutDatabase(env.process()), null, "test", ledger);
        final ManagementServer server = ManagementServer.serve(ManagementSettings.parse("true", "127.0.0.1:0", TOKEN),
                env.process(), methods, Duration.ofSeconds(5));
        opened.add(server);
        return server.address();
    }

    /** A socket with a 4 KiB receive window that has done the upgrade with the token and read its answer. */
    private Socket connect(InetSocketAddress address) throws IOException {
        final Socket socket = new Socket(Proxy.NO_PROXY);
        opened.add(socket);
        socket.setReceiveBufferSize(4_096);
        socket.connect(address, 5_000);
        socket.setSoTimeout(10_000);
        socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
                + "Authorization: Bearer " + TOKEN + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        final ByteArrayOutputStream answer = new ByteArrayOutputStream();
        final InputStream in = socket.getInputStream();
        while (!answer.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) answer.write(in.read());
        assertTrue(answer.toString(StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 101"),
                answer.toString(StandardCharsets.ISO_8859_1));
        return socket;
    }

    /** One final text frame from the client, masked; the payload is under 64 KiB. */
    private static byte[] textFrame(byte[] payload) {
        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81);
        frame.write(0x80 | 126);
        frame.write(payload.length >>> 8);
        frame.write(payload.length & 0xFF);
        final byte[] mask = {0x11, 0x22, 0x33, 0x44};
        frame.writeBytes(mask);
        for (int index = 0; index < payload.length; index++) frame.write(payload[index] ^ mask[index % 4]);
        return frame.toByteArray();
    }

    /** The WARN lines closing a connection for its backlog so far; the appender adds under its own lock. */
    private List<String> closings() {
        synchronized (lines) {
            return lines.list.stream().filter(line -> line.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage).filter(line -> CLOSED.matcher(line).matches()).toList();
        }
    }

    @Test
    void repliesSentAtOnceQueueAtMostOneMessagePastTheByteBound(Env env) throws Exception {
        // The server's frames are not masked: 2 B of header and 2 of length before the reply
        final long reply = 4L + ("{\"jsonrpc\":\"2.0\",\"id\":\"" + ID + "\",\"result\":[]}").length();
        final byte[] request = textFrame(("{\"jsonrpc\":\"2.0\",\"id\":\"" + ID + "\",\"method\":\"minecraft:players\"}")
                .getBytes(StandardCharsets.UTF_8));
        // A server of its own each round: its log budget writes one closing line per 10 s
        for (int round = 0; round < ROUNDS; round++) {
            // Every call reaches the ledger before its reply is made: held there, the replies line up
            final CountDownLatch arrived = new CountDownLatch(CALLS);
            final CountDownLatch release = new CountDownLatch(1);
            final BiConsumer<String, Map<String, ?>> ledger = (_, _) -> {
                arrived.countDown();
                try {
                    release.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            };
            final Socket socket = connect(serve(env, ledger));
            try {
                for (int call = 0; call < CALLS; call++) socket.getOutputStream().write(request);
                assertTrue(arrived.await(30, TimeUnit.SECONDS), "every call is held before its reply, round " + round);
            } finally {
                release.countDown();
            }

            final int closed = round + 1;
            await(() -> closings().size() >= closed, "the connection closed for its backlog, round " + round);
            final List<String> closings = closings();
            assertEquals(closed, closings.size(), closings.toString());
            final Matcher matcher = CLOSED.matcher(closings.getLast());
            assertTrue(matcher.matches());
            final long bytes = Long.parseLong(matcher.group(2));
            assertTrue(bytes >= ManagementServer.MAX_QUEUED_BYTES, "closed at the byte bound: " + bytes + " B, round " + round);
            assertTrue(bytes < ManagementServer.MAX_QUEUED_BYTES + reply,
                    "at most one reply of " + reply + " B past the bound, not " + bytes + " B, round " + round);
        }
    }
}
