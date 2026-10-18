package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import com.sun.net.httpserver.HttpServer;
import io.github.bearisbug.synaxis.SessionServer;
import io.github.bearisbug.synaxis.network.player.SessionClient;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The 10 s summary of the session verification ({@code docs/DESIGN-PROD.md} {@code API-PROD-002}),
 * read through the host's logback: a window with unavailable answers ends its line with the cause
 * of the last one, so that an unreachable session server, a wrong URL, a rate limit and a login
 * timeout can be told apart.
 */
public class SessionClientLogTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    @Test
    public void theSummaryEndsWithTheCauseOfTheLastUnavailableAnswer() throws Exception {
        final AtomicInteger status = new AtomicInteger(503);
        final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hasJoined", exchange -> {
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        final URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hasJoined");
        try (LogCapture log = LogCapture.of(SessionClient.class)) {
            final SessionClient client = new SessionClient(SessionServer.at(url));
            final long start = 5 * SECOND;
            client.tick(start);

            assertInstanceOf(SessionClient.Result.Unavailable.class, verify(client));
            client.tick(start + 10 * SECOND);
            // The cause belongs to its window: the next one has no unavailable answer and says so
            status.set(204);
            assertInstanceOf(SessionClient.Result.Unverified.class, verify(client));
            client.tick(start + 20 * SECOND);

            assertEquals(List.of(
                    "Session verification in the last 10 s: verified=0 unverified=0 unavailable=1 session_server="
                            + url + " last_unavailable_cause=status 503",
                    "Session verification in the last 10 s: verified=0 unverified=1 unavailable=0 session_server="
                            + url + " last_unavailable_cause=-"), log.messagesAt(Level.WARN));
        } finally {
            server.stop(0);
        }
    }

    private static SessionClient.Result verify(SessionClient client) throws InterruptedException {
        return client.verify("Notch", "hash", null, System.currentTimeMillis() + 10_000, () -> true);
    }
}
