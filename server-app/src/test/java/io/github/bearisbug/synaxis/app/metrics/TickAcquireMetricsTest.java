package io.github.bearisbug.synaxis.app.metrics;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.thread.TickThread;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/DESIGN-PROD.md} {@code API-PROD-015}: {@code synaxis_tick_acquire_seconds} has a series
 * per tick thread and one for the tick scheduler, and a tick thread kept waiting shows in its own
 * series and in the whole tick's ({@code API-PROD-016}).
 */
public class TickAcquireMetricsTest {

    private static final String ACQUIRE = "synaxis_tick_acquire_seconds";
    private static final long HOLD_MILLIS = 50;

    /**
     * On the grid with four tick threads, two entities in cells (0, 0) and (1, 0), thread ids 0 and
     * 3. After one tick, a thread that is not a tick thread holds the lock of the first entity's tick
     * thread when that thread starts the next tick, until it is queued on its own lock and a measured
     * sleep more. The lock itself, not the entity through its acquirable: an acquirer holding the
     * entity from before the tick holds up the dispatcher instead, which hands out no tick before it
     * let go.
     */
    @Test
    @Timeout(60)
    public void aTickThreadKeptWaitingShowsInItsSeriesAndTheWholeTicks() throws Exception {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true)
                .threadProvider(ServerConfig.ThreadProviderKind.GRID).dispatcherThreads(4).build());
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.dispatcher().start();
        try (HostMetrics metrics = HostMetrics.register(process, new HostMetrics.Sources(() -> 0,
                PlayerStore.inMemory(process, (_, _) -> {
                }), null, null, RedisLink.disabled("acquire")))) {
            final InetSocketAddress bound = metrics.serve(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            final InstanceContainer instance = process.instance().createInstanceContainer();
            instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
            final Entity held = new Entity(EntityType.ZOMBIE);
            held.setInstance(instance, new Pos(8, 42, 8)).join();
            new Entity(EntityType.ZOMBIE).setInstance(instance, new Pos(16 * 16 + 8, 42, 8)).join();
            process.ticker().tick(System.nanoTime());
            final TickThread heldThread = held.acquirable().assignedThread();
            assertEquals("Syn-Tick-0", heldThread.getName());

            final CountDownLatch holding = new CountDownLatch(1);
            final AtomicLong slept = new AtomicLong();
            final ReentrantLock tickLock = heldThread.lock();
            final Thread holder = Thread.ofPlatform().name("holder").start(() -> {
                tickLock.lock();
                try {
                    holding.countDown();
                    while (!tickLock.hasQueuedThread(heldThread)) Thread.onSpinWait();
                    final long since = System.nanoTime();
                    Thread.sleep(HOLD_MILLIS);
                    slept.set(System.nanoTime() - since);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    tickLock.unlock();
                }
            });
            holding.await();
            process.ticker().tick(System.nanoTime());
            holder.join();

            // A tick thread records right after its own tick, which may be after the tick returned
            final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
            final URI uri = URI.create("http://127.0.0.1:" + bound.getPort() + HostMetrics.PATH);
            String text = get(http, uri);
            for (int i = 0; i < 500 && (value(text, "_count", "Syn-Tick-0") < 2 || value(text, "_count", "Syn-Tick-3") < 2); i++) {
                Thread.sleep(10);
                text = get(http, uri);
            }
            assertEquals(2, value(text, "_count", "Syn-Tick-0"));
            assertEquals(2, value(text, "_count", "Syn-Tick-3"));
            assertEquals(0, value(text, "_count", "Syn-Tick-1"), "a thread without a partition does not tick");
            assertEquals(0, value(text, "_count", "Syn-Tick-2"));
            assertEquals(2, value(text, "_count", "Syn-TickScheduler"));

            final double sleptSeconds = slept.get() / 1e9;
            assertTrue(sleptSeconds >= HOLD_MILLIS / 1e3, "the holder slept: " + slept.get() + " ns");
            final double waited = value(text, "_sum", "Syn-Tick-0");
            assertTrue(waited >= sleptSeconds, "Syn-Tick-0 waited out the holder's sleep of " + sleptSeconds + " s: " + waited);
            assertEquals(0.0, value(text, "_sum", "Syn-Tick-3"), "Syn-Tick-3 waited for nothing");
            assertEquals(waited, value(text, "_sum", "Syn-TickScheduler"), 1e-6,
                    "the whole tick carries the longest wait of a tick thread");
            assertTrue(value(text, "_max", "Syn-TickScheduler") >= sleptSeconds);
            assertTrue(text.lines().anyMatch(line -> line.startsWith(ACQUIRE + "{") && line.contains("quantile=\"0.99\"")
                    && line.contains("thread=\"Syn-Tick-0\"")), "the p99 of a tick thread");
        } finally {
            process.stop();
        }
    }

    private static String get(HttpClient http, URI uri) throws Exception {
        final HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private static double value(String text, String suffix, String thread) {
        final Matcher matcher = Pattern.compile("^" + ACQUIRE + suffix + "\\{thread=\"" + thread + "\"} (\\S+)$",
                Pattern.MULTILINE).matcher(text);
        if (!matcher.find()) throw new AssertionError("no " + ACQUIRE + suffix + " of " + thread);
        return Double.parseDouble(matcher.group(1));
    }
}
