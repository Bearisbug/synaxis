package io.github.bearisbug.synaxis.app.metrics;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A peer that stops halfway does not take the metrics endpoint down ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-015}, §15 指标端点): every scrape runs on a thread of its own, so the scrape of
 * the next peer is answered at once, and a scrape that has run for
 * {@link HostMetrics#EXCHANGE_LIMIT} is cut off, its connection closed. One thread without a time
 * limit answered no one else for as long as such a peer held on.
 */
public class MetricsEndpointLimitTest {

    /** What a scrape that nothing holds takes, with room for a loaded machine. */
    private static final Duration ANSWERED_WITHIN = Duration.ofSeconds(5);
    /** The limit, one timer round of slack and the time the close takes to arrive. */
    private static final Duration CLOSED_WITHIN = HostMetrics.EXCHANGE_LIMIT.plusSeconds(3);

    @Test
    @Timeout(60)
    public void aPeerThatStopsHalfwayThroughItsRequestHoldsNoOtherScrapeAndIsCutOff() throws Exception {
        try (Served served = Served.start(); Socket half = new Socket()) {
            half.connect(served.address);
            half.getOutputStream().write("GET /metrics HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
            half.getOutputStream().flush();
            final long sent = System.nanoTime();
            // Its exchange is reading the rest of the request by now
            Thread.sleep(300);

            assertScrapeAnswered(served);
            half.setSoTimeout((int) CLOSED_WITHIN.toMillis());
            assertClosed(half.getInputStream(), "a request never finished");
            final long closedAfter = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent);
            assertTrue(closedAfter >= HostMetrics.EXCHANGE_LIMIT.toMillis() - 1_000,
                    "cut off at the limit, not before: " + closedAfter + " ms");
        }
    }

    @Test
    @Timeout(60)
    public void aPeerThatReadsNoAnswerHoldsNoOtherScrapeAndIsCutOff() throws Exception {
        try (Served served = Served.start(); Socket deaf = new Socket()) {
            deaf.setReceiveBufferSize(1024);
            deaf.connect(served.address);
            final byte[] request = "GET /metrics HTTP/1.1\r\nHost: metrics\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            // Far more answers than the socket buffers of both ends hold
            for (int i = 0; i < 200; i++) deaf.getOutputStream().write(request);
            deaf.getOutputStream().flush();
            Thread.sleep(300);

            assertScrapeAnswered(served);
            // Reading earlier would let the answers through; past the limit the connection is gone
            Thread.sleep(CLOSED_WITHIN.toMillis());
            deaf.setSoTimeout((int) ANSWERED_WITHIN.toMillis());
            assertClosed(deaf.getInputStream(), "answers never read");
        }
    }

    private static void assertScrapeAnswered(Served served) throws IOException, InterruptedException {
        final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        final long start = System.nanoTime();
        final HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + served.address.getPort() + HostMetrics.PATH)).timeout(ANSWERED_WITHIN).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("synaxis_tick_duration_seconds"), response.body());
        assertTrue(tookMillis < ANSWERED_WITHIN.toMillis(), "answered while another peer held on: " + tookMillis + " ms");
    }

    /** Reads until the server closes the connection; an answer or two may still come first. */
    private static void assertClosed(InputStream in, String what) throws IOException {
        final byte[] buffer = new byte[64 * 1024];
        try {
            while (in.read(buffer) >= 0) {
                // what the server wrote before it closed
            }
        } catch (SocketTimeoutException e) {
            throw new AssertionError("the server never closed the connection of a peer with " + what, e);
        } catch (SocketException _) {
            // Reset: the server closed with bytes of the peer it had not read
        }
    }

    private record Served(ServerProcess process, HostMetrics metrics, InetSocketAddress address)
            implements AutoCloseable {

        static Served start() throws IOException {
            final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true).build());
            final HostMetrics metrics = HostMetrics.register(process, new HostMetrics.Sources(() -> 0,
                    PlayerStore.inMemory(process, (_, _) -> {
                    }), null, null, RedisLink.disabled("limit")));
            return new Served(process, metrics, metrics.serve(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)));
        }

        @Override
        public void close() {
            metrics.close();
        }
    }
}
