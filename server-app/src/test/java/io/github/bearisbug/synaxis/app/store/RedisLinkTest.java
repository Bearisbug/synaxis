package io.github.bearisbug.synaxis.app.store;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code SYNAXIS_REDIS_URL} that is not one ({@code docs/DESIGN-PROD.md} {@code API-PROD-006}):
 * the shard refuses to start with the variable's name, never with the URL, which carries the
 * password. What {@link java.net.URI} and {@link Integer#parseInt} would say quotes the URL, and the
 * refusal line is logged with the whole cause chain, so the refusal has no cause. Also the queue of
 * the presence writes, which a Redis that does not answer fills.
 */
class RedisLinkTest {

    private static final String REFUSAL = "SYNAXIS_REDIS_URL is not redis://[user:password@]host:port[/database]; "
            + "percent-encode every character of the password but letters, digits and -._~";

    @Test
    void aUrlThatIsNotOneIsRefusedWithoutThePassword() {
        // A '/' as openssl rand -base64 makes, a '%' as a password generator makes, a '#'
        for (String url : List.of("redis://:Ab3+/x9cd=@valkey:6379", "redis://:p%zz@valkey:6379",
                "redis://:Ab3#x9cd@valkey:6379")) {
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> RedisLink.connect(url, "r01-c1").close(), url);
            assertEquals(REFUSAL, refused.getMessage(), url);
            assertNull(refused.getCause(), url);
        }
    }

    /** The password goes to Redis decoded: {@code Ab3+x9cd=}, as the client's first command spells it. */
    @Test
    void aPercentEncodedPasswordIsTaken() throws Exception {
        try (ServerSocket fake = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final CompletableFuture<String> received = CompletableFuture.supplyAsync(() -> firstCommand(fake));
            try (RedisLink link = assertDoesNotThrow(() -> RedisLink.connect(
                    "redis://:Ab3%2Bx9cd%3D@127.0.0.1:" + fake.getLocalPort() + "/2", "r01-c1"))) {
                assertFalse(link.publishWorldFlag("synaxis:test", "x"), "the fake answers nothing");
            }
            final String command = received.get(10, TimeUnit.SECONDS);
            assertTrue(command.contains("\r\nAb3+x9cd=\r\n"), "what the client sent first: " + command);
        }
    }

    /**
     * A Redis that takes the connection and never answers holds the presence thread for the whole
     * timeout: the writes after it are queued without waiting, and past the queue the oldest one
     * goes, with one warning.
     */
    @Test
    void aFullPresenceQueueDropsItsOldestWriteWithOneWarning() throws Exception {
        final ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        ((Logger) LoggerFactory.getLogger(RedisLink.class)).addAppender(log);
        final long tookMillis;
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
             RedisLink link = RedisLink.connect("redis://127.0.0.1:" + silent.getLocalPort(), "r01-c1")) {
            final long start = System.nanoTime();
            // One taken by the thread, a full queue behind it, one more
            for (int i = 0; i < RedisLink.PRESENCE_QUEUE + 2; i++) link.presence(UUID.randomUUID(), 1, "online");
            tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        } finally {
            ((Logger) LoggerFactory.getLogger(RedisLink.class)).detachAppender(log);
        }

        assertTrue(tookMillis < RedisLink.TIMEOUT_MS, "queued, not waited for: " + tookMillis + " ms");
        final List<ILoggingEvent> dropped = log.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("The presence queue is full")).toList();
        assertEquals(1, dropped.size(), log.list.toString());
        assertEquals(Level.WARN, dropped.getFirst().getLevel());
    }

    /** What the first connection sends until it has been quiet for half a second; then it is closed. */
    private static String firstCommand(ServerSocket fake) {
        try (Socket client = fake.accept()) {
            client.setSoTimeout(500);
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final byte[] buffer = new byte[256];
            try {
                for (int read; (read = client.getInputStream().read(buffer)) >= 0; ) bytes.write(buffer, 0, read);
            } catch (SocketTimeoutException _) {
                // The client waits for an answer that never comes
            }
            return bytes.toString(StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
