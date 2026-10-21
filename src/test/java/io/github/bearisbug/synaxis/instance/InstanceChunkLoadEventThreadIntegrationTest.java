package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.event.instance.InstanceChunkLoadEvent;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class InstanceChunkLoadEventThreadIntegrationTest {
    private static final int COLUMNS = 50;
    private static final int ROWS = 40;
    /** How far along x a listener loads another chunk from the event of a chunk the test thread asked for. */
    private static final int OFFSET = 100;

    // The load event of a parallel loader's chunk is called on a virtual thread, never on the thread asking for the
    // chunk: there it would run inside the computation of the instance's loading map, where a listener loading
    // another chunk of the instance is a recursive update of that map
    @Test
    public void loadEventOfAParallelLoadIsNeverCalledOnTheRequestingThread(Env env) throws InterruptedException {
        final Queue<Throwable> handled = new ConcurrentLinkedQueue<>();
        env.process().exception().setExceptionHandler(handled::add);
        var instance = env.createEmptyInstance(new ChunkLoader() {
            @Override
            public Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
                return new DynamicChunk(instance, chunkX, chunkZ);
            }

            @Override
            public void saveChunk(Chunk chunk) {
            }

            @Override
            public boolean supportsParallelLoading() {
                return true;
            }
        });
        final int events = 2 * COLUMNS * ROWS;
        final Map<Long, Thread> requesters = new ConcurrentHashMap<>();
        final AtomicInteger onRequester = new AtomicInteger();
        final AtomicInteger onPlatform = new AtomicInteger();
        final CountDownLatch called = new CountDownLatch(events);
        instance.eventNode().addListener(InstanceChunkLoadEvent.class, event -> {
            final Thread thread = Thread.currentThread();
            if (thread == requesters.get(CoordConversion.chunkIndex(event.getChunkX(), event.getChunkZ()))) {
                onRequester.incrementAndGet();
            }
            if (!thread.isVirtual()) onPlatform.incrementAndGet();
            called.countDown();
            if (event.getChunkX() < OFFSET) load(instance, requesters, event.getChunkX() + OFFSET, event.getChunkZ());
        });

        for (int x = 0; x < COLUMNS; x++) {
            for (int z = 0; z < ROWS; z++) load(instance, requesters, x, z);
        }

        final boolean all = called.await(30, TimeUnit.SECONDS);
        assertEquals(List.of(), List.copyOf(handled), "nothing reaches the exception handler");
        assertTrue(all, (events - called.getCount()) + " of " + events + " load events were called");
        assertEquals("0 on the thread that asked for the chunk, 0 on a platform thread",
                onRequester.get() + " on the thread that asked for the chunk, " + onPlatform.get() + " on a platform thread",
                "where the " + events + " load events were called");
    }

    private static void load(Instance instance, Map<Long, Thread> requesters, int chunkX, int chunkZ) {
        requesters.put(CoordConversion.chunkIndex(chunkX, chunkZ), Thread.currentThread());
        var _ = instance.loadChunk(chunkX, chunkZ);
    }
}
