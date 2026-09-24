package io.github.bearisbug.synaxis.app.metrics;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/DESIGN-PROD.md} §19 "指标端点只在内网暴露并含每线程 MSPT" ({@code REQ-PROD-018}):
 * with four dispatcher threads, {@code GET /metrics} answers Prometheus text with one
 * {@code synaxis_tick_duration_seconds} series per tick thread and one for the tick scheduler.
 */
public class MetricsEndpointTest {

    private static final int CHUNKS = 8;
    private static final int TICKS = 5;

    @Test
    @Timeout(60)
    public void theEndpointServesATickSeriesPerThreadAndTheStoreAndAuthFamilies() throws Exception {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true).dispatcherThreads(4)
                .build());
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.dispatcher().start();
        try (HostMetrics metrics = HostMetrics.register(process, new HostMetrics.Sources(() -> 7,
                PlayerStore.inMemory(process, (_, _) -> {
                }), null, null, RedisLink.disabled("endpoint")))) {
            final InetSocketAddress bound = metrics.serve(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            final InstanceContainer instance = process.instance().createInstanceContainer();
            instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
            for (int chunkX = 0; chunkX < CHUNKS; chunkX++)
                new Entity(EntityType.ZOMBIE).setInstance(instance, new Pos(chunkX * 16, 42, 0)).join();
            // Every thread holds a chunk now: the counter provider spreads eight over four
            for (int i = 0; i < TICKS; i++) process.ticker().tick(System.nanoTime());
            process.connection().gate().handshakeTimedOut();

            final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
            final URI uri = URI.create("http://127.0.0.1:" + bound.getPort() + HostMetrics.PATH);
            final HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
            final String text = response.body();
            for (String thread : new String[]{"Syn-Tick-0", "Syn-Tick-1", "Syn-Tick-2", "Syn-Tick-3", "Syn-TickScheduler"})
                assertEquals(TICKS, count(text, thread), "ticks timed on " + thread);
            assertTrue(text.lines().anyMatch(line -> line.startsWith("synaxis_tick_duration_seconds{")
                    && line.contains("quantile=\"0.99\"") && line.contains("thread=\"Syn-Tick-0\"")), "the p99 of a tick thread");
            assertTrue(text.contains("# TYPE synaxis_db_statement_seconds summary"));
            assertTrue(text.contains("synaxis_auth_hasjoined_total{result=\"verified\"} 0.0"));
            assertTrue(text.contains("synaxis_gate_rejections_total{reason=\"handshake_timeout\"} 1.0"),
                    "the gate's own count");
            assertTrue(text.contains("synaxis_ledger_dropped_total 7.0"), "the ledger's own count");
            assertTrue(text.contains("synaxis_connections{state=\"play\"} 0.0"));

            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + bound.getPort()
                    + "/metrics/more")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(405, http.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            assertThrows(IOException.class, () -> HostMetrics.register(process, new HostMetrics.Sources(() -> 0,
                            PlayerStore.inMemory(process, (_, _) -> {
                            }), null, null, RedisLink.disabled("second"))).serve(bound),
                    "a second endpoint on a bound address fails");
        } finally {
            process.stop();
        }
    }

    @Test
    public void theBindIsHostAndPort() {
        final InetSocketAddress byDefault = HostMetrics.bindAddress(HostMetrics.DEFAULT_BIND);
        assertEquals("127.0.0.1:9464", byDefault.getHostString() + ":" + byDefault.getPort());
        final InetSocketAddress any = HostMetrics.bindAddress(" 10.0.0.5:0 ");
        assertEquals("10.0.0.5:0", any.getHostString() + ":" + any.getPort());
        for (String wrong : new String[]{"127.0.0.1", "127.0.0.1:port", "127.0.0.1:9464/metrics", ":9464"})
            assertThrows(IllegalArgumentException.class, () -> HostMetrics.bindAddress(wrong), wrong);
    }

    private static long count(String text, String thread) {
        final Matcher matcher = Pattern.compile("^synaxis_tick_duration_seconds_count\\{thread=\"" + thread
                + "\"} (\\d+)(\\.0)?$", Pattern.MULTILINE).matcher(text);
        if (!matcher.find()) throw new AssertionError("no tick count of " + thread);
        return Long.parseLong(matcher.group(1));
    }
}
