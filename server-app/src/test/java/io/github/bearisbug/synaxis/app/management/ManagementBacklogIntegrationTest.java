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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.bearisbug.synaxis.app.management.ManagementClient.await;
import static io.github.bearisbug.synaxis.app.management.ManagementSettingsTest.TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The outbound queue of a management connection is bounded ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-022}, §15 the management row, §19 the scenario of a peer that does not read).
 * The client here writes raw frames on a socket whose receive window is 4 KiB and reads only when
 * the test says so: a peer that never reads fills the kernel buffers first, then the server's
 * queue, which Java-WebSocket alone would let grow until the heap is full.
 */
@EnvTest
class ManagementBacklogIntegrationTest {

    private static final int PING = 0x9;
    private static final int TEXT = 0x1;
    private static final Pattern CLOSED = Pattern.compile(
            "Management connection closed with 1008: remote=\\S+ has (\\d+) frames and (\\d+) bytes queued that it does not read .*");

    @TempDir
    Path workingDirectory;

    private final List<Map<String, ?>> events = new CopyOnWriteArrayList<>();
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

    private InetSocketAddress serve(Env env) {
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
                HostModeration.withoutDatabase(env.process()), null, "test", (_, attributes) -> events.add(attributes));
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

    /** One final frame from the client, masked as RFC 6455 wants. */
    private static byte[] frame(int opcode, byte[] payload) {
        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | opcode);
        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else {
            frame.write(0x80 | 126);
            frame.write(payload.length >>> 8);
            frame.write(payload.length & 0xFF);
        }
        final byte[] mask = {0x11, 0x22, 0x33, 0x44};
        frame.writeBytes(mask);
        for (int index = 0; index < payload.length; index++) frame.write(payload[index] ^ mask[index % 4]);
        return frame.toByteArray();
    }

    private static byte[] times(byte[] frame, int count) {
        final ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (int copy = 0; copy < count; copy++) all.writeBytes(frame);
        return all.toByteArray();
    }

    /** Writes the bytes again and again, reading nothing, until the server closes or {@code most} writes are done. */
    private static int writeUntilClosed(Socket socket, byte[] bytes, int most) {
        for (int written = 0; written < most; written++) {
            try {
                socket.getOutputStream().write(bytes);
            } catch (IOException _) {
                return written;
            }
        }
        return most;
    }

    /** The WARN lines closing a connection for its backlog so far; the appender adds under its own lock. */
    private List<String> closings() {
        synchronized (lines) {
            return lines.list.stream().filter(line -> line.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage).filter(line -> CLOSED.matcher(line).matches()).toList();
        }
    }

    /** The frames and the bytes the one closing line reports, once it is written. */
    private long[] closedWith() {
        await(() -> !closings().isEmpty(), "a connection closed for its backlog");
        final List<String> closings = closings();
        assertEquals(1, closings.size(), closings.toString());
        final Matcher matcher = CLOSED.matcher(closings.getFirst());
        assertTrue(matcher.matches());
        return new long[]{Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2))};
    }

    @Test
    void aPeerThatReadsNoPongIsClosedAtTheFrameLimit(Env env) throws Exception {
        final Socket socket = connect(serve(env));
        // 64 pings of 125 B a write; each pong is a 127 B frame, so frames run out long before bytes
        final int most = 200_000 / 64;
        final int writes = writeUntilClosed(socket, times(frame(PING, "p".repeat(125).getBytes(StandardCharsets.US_ASCII)), 64), most);

        assertTrue(writes < most, "the server never closed the connection within " + most * 64 + " pings");
        final long[] closed = closedWith();
        assertEquals(ManagementServer.MAX_QUEUED_FRAMES, closed[0], "frames when closed");
        assertTrue(closed[1] < ManagementServer.MAX_QUEUED_BYTES, "bytes when closed: " + closed[1]);
    }

    @Test
    void aPeerThatReadsNoReplyIsClosedAtTheByteLimit(Env env) throws Exception {
        final Socket socket = connect(serve(env));
        // A method that does not exist is answered with the id it was given and runs nothing: a 16 KiB
        // id makes every reply 16 KiB, so bytes run out long before frames
        final String request = "{\"jsonrpc\":\"2.0\",\"id\":\"" + "i".repeat(16_384) + "\",\"method\":\"synaxis:nothing\"}";
        final int most = 2_000;
        final int writes = writeUntilClosed(socket, frame(TEXT, request.getBytes(StandardCharsets.UTF_8)), most);

        assertTrue(writes < most, "the server never closed the connection within " + most + " requests");
        final long[] closed = closedWith();
        assertTrue(closed[0] < ManagementServer.MAX_QUEUED_FRAMES, "frames when closed: " + closed[0]);
        assertTrue(closed[1] >= ManagementServer.MAX_QUEUED_BYTES, "bytes when closed: " + closed[1]);
        assertEquals(List.of(), events, "nothing ran");
    }

    @Test
    void aPeerThatReadsWhatItIsSentKeepsItsConnection(Env env) throws Exception {
        final Socket socket = connect(serve(env));
        final InputStream in = socket.getInputStream();
        final byte[] pings = times(frame(PING, "p".repeat(125).getBytes(StandardCharsets.US_ASCII)), 100);
        // 10,000 pongs, 1,270,000 B: past both limits in all, never more than 100 frames queued at once
        for (int round = 0; round < 100; round++) {
            socket.getOutputStream().write(pings);
            final byte[] pongs = in.readNBytes(127 * 100);
            assertEquals(127 * 100, pongs.length, "round " + round);
            for (int pong = 0; pong < 100; pong++) {
                assertEquals(0x8A, pongs[pong * 127] & 0xFF, "a final pong, round " + round);
                assertEquals(125, pongs[pong * 127 + 1], "its length, round " + round);
            }
        }
        socket.getOutputStream().write(frame(TEXT,
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"minecraft:server/status\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0x81, in.read(), "a final text frame");
        final int first = in.read();
        final int length = first < 126 ? first : (in.read() << 8) | in.read();
        final String reply = new String(in.readNBytes(length), StandardCharsets.UTF_8);
        assertTrue(reply.startsWith("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{"), reply);
        assertEquals(List.of(), closings(), "no connection closed for its backlog");
    }
}
