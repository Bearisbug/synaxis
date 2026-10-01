package io.github.bearisbug.synaxis.app.metrics;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The metrics endpoint is bounded in connections and keeps no JVM alive
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-015}, §15 the metrics row, §19 the scenario of the
 * caps of the operations ports, §28.2 the row of a game port that cannot be bound). With
 * {@value #CAP} connections open, a new one is closed as it is accepted instead of waiting out the
 * JDK's 30 s idle timer, and the JDK's dispatcher thread is a daemon: started from main, it kept a
 * process whose start had failed alive and listening.
 */
public class MetricsEndpointGuardsTest {

    /** The connections the endpoint holds at most (§15). */
    private static final int CAP = 64;

    @Test
    @Timeout(60)
    public void aConnectionPastTheCapIsClosedAtOnce() throws Exception {
        try (Served served = Served.start()) {
            final List<Socket> idle = new ArrayList<>();
            try {
                // Accepted in this order, so all of them are counted by the time the next one is
                for (int i = 0; i < CAP; i++) idle.add(open(served.address));
                try (Socket past = open(served.address)) {
                    final long start = System.nanoTime();
                    try {
                        assertEquals(-1, past.getInputStream().read(), "nothing is answered");
                    } catch (SocketTimeoutException e) {
                        throw new AssertionError("still open after 2 s", e);
                    } catch (SocketException _) {
                        // Reset: closed all the same
                    }
                    final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    assertTrue(tookMillis < 1_000, "closed as it is accepted, not by the idle timer: " + tookMillis + " ms");
                }
            } finally {
                for (Socket socket : idle) socket.close();
            }
            // Closed by their peers, they no longer count
            assertScrapeAnsweredWithin(served, Duration.ofSeconds(10));
        }
    }

    @Test
    public void theDispatcherThreadIsADaemon() throws Exception {
        final Set<Thread> before = Thread.getAllStackTraces().keySet();
        try (Served served = Served.start()) {
            final List<Thread> dispatchers = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> !before.contains(thread) && "HTTP-Dispatcher".equals(thread.getName())).toList();
            assertEquals(1, dispatchers.size(), "the dispatcher of this endpoint: " + dispatchers + " at " + served.address);
            assertTrue(dispatchers.getFirst().isDaemon(), "the dispatcher keeps no JVM alive");
        }
    }

    private static Socket open(InetSocketAddress address) throws IOException {
        final Socket socket = new Socket(Proxy.NO_PROXY);
        socket.connect(address, 5_000);
        socket.setSoTimeout(2_000);
        return socket;
    }

    private static void assertScrapeAnsweredWithin(Served served, Duration within) throws InterruptedException {
        final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        final HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + served.address.getPort()
                + HostMetrics.PATH)).timeout(Duration.ofSeconds(5)).GET().build();
        final long deadline = System.nanoTime() + within.toNanos();
        while (true) {
            try {
                final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("synaxis_tick_duration_seconds"), response.body());
                return;
            } catch (IOException e) {
                if (System.nanoTime() > deadline) throw new AssertionError("no scrape answered within " + within, e);
                Thread.sleep(100);
            }
        }
    }

    private record Served(ServerProcess process, HostMetrics metrics, InetSocketAddress address)
            implements AutoCloseable {

        static Served start() throws IOException {
            final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true).build());
            final HostMetrics metrics = HostMetrics.register(process, new HostMetrics.Sources(() -> 0,
                    PlayerStore.inMemory(process, (_, _) -> {
                    }), null, null, RedisLink.disabled("guards")));
            return new Served(process, metrics, metrics.serve(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)));
        }

        @Override
        public void close() {
            metrics.close();
        }
    }
}
