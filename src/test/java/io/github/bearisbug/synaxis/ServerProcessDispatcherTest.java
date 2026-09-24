package io.github.bearisbug.synaxis;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-014: the dispatcher thread count is per process, so two processes sharing a JVM tick their own
 * partitions on their own threads.
 */
public class ServerProcessDispatcherTest {

    private static final int CHUNKS = 8;
    private static final int TICKS = 5;

    @Test
    @Timeout(60)
    public void twoProcessesTickWithTheirOwnThreadCount() {
        final ServerProcess single = start(1);
        final ServerProcess dual = start(2);
        try {
            assertEquals(1, single.dispatcher().threads().size());
            assertEquals(2, dual.dispatcher().threads().size());

            final List<Recorder> singleEntities = populate(single);
            final List<Recorder> dualEntities = populate(dual);

            for (int i = 0; i < TICKS; i++) {
                single.ticker().tick(System.nanoTime());
                dual.ticker().tick(System.nanoTime());
            }

            final Set<Thread> singleThreads = tickThreads(singleEntities);
            final Set<Thread> dualThreads = tickThreads(dualEntities);

            assertEquals(1, singleThreads.size(), "the one thread process must tick everything on one thread");
            assertEquals(2, dualThreads.size(), "the two thread process must spread its partitions over both threads");
            assertTrue(single.dispatcher().threads().containsAll(singleThreads));
            assertTrue(dual.dispatcher().threads().containsAll(dualThreads));
            assertFalse(singleThreads.removeAll(dualThreads), "neither process may tick on the other's threads");
        } finally {
            dual.stop();
            single.stop();
        }
    }

    /**
     * {@code docs/DESIGN-PROD.md} {@code REQ-PROD-020}: with the grid, chunks (0, 0) and (15, 15) are
     * in one cell of 16 by 16 chunks and tick on one thread, chunk (16, 0) is in the next cell, whose
     * id 3 lands on another of the six threads. The counter gives each of the three its own
     * thread, so the same placement tells the two providers apart.
     */
    @Test
    @Timeout(60)
    public void theGridKeepsACellOnOneThread() {
        final ServerProcess grid = start(ServerConfig.builder().threadProvider(ServerConfig.ThreadProviderKind.GRID)
                .dispatcherThreads(6));
        final ServerProcess counter = start(ServerConfig.builder().dispatcherThreads(6));
        try {
            final List<Recorder> onGrid = place(grid, 0, 0, 15, 15, 16, 0);
            final List<Recorder> onCounter = place(counter, 0, 0, 15, 15, 16, 0);
            for (int i = 0; i < TICKS; i++) {
                grid.ticker().tick(System.nanoTime());
                counter.ticker().tick(System.nanoTime());
            }

            assertEquals(Set.of(grid.dispatcher().threads().get(0)), onGrid.get(0).tickThreads);
            assertEquals(onGrid.get(0).tickThreads, onGrid.get(1).tickThreads,
                    "chunks (0, 0) and (15, 15) are in one cell");
            assertEquals(Set.of(grid.dispatcher().threads().get(3)), onGrid.get(2).tickThreads,
                    "chunk (16, 0) is in cell (1, 0)");
            assertEquals(grid.dispatcher().threads().get(0), onGrid.get(1).acquirable().assignedThread());

            assertEquals(3, tickThreads(onCounter).size(), "the counter gives every new chunk the next thread");
        } finally {
            counter.stop();
            grid.stop();
        }
    }

    private static List<Recorder> place(ServerProcess process, int... chunks) {
        final InstanceContainer instance = process.instance().createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
        final List<Recorder> entities = new ArrayList<>();
        for (int i = 0; i < chunks.length; i += 2) {
            final Recorder entity = new Recorder();
            entity.setInstance(instance, new Pos(chunks[i] * 16 + 8, 42, chunks[i + 1] * 16 + 8)).join();
            entities.add(entity);
        }
        return entities;
    }

    private static ServerProcess start(int dispatcherThreads) {
        return start(ServerConfig.builder().dispatcherThreads(dispatcherThreads));
    }

    private static ServerProcess start(ServerConfig.Builder config) {
        final ServerProcess process = ServerProcess.of(config.insideTest(true).build());
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.dispatcher().start();
        return process;
    }

    private static List<Recorder> populate(ServerProcess process) {
        final InstanceContainer instance = process.instance().createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
        final List<Recorder> entities = new ArrayList<>(CHUNKS);
        for (int chunkX = 0; chunkX < CHUNKS; chunkX++) {
            final Recorder entity = new Recorder();
            entity.setInstance(instance, new Pos(chunkX * 16, 42, 0)).join();
            entities.add(entity);
        }
        return entities;
    }

    private static Set<Thread> tickThreads(List<Recorder> entities) {
        final Set<Thread> threads = new HashSet<>();
        for (Recorder entity : entities) {
            assertFalse(entity.tickThreads.isEmpty(), "every entity must have been ticked");
            threads.addAll(entity.tickThreads);
        }
        for (Thread thread : threads) assertTrue(thread instanceof TickThread, "ticks must run on a tick thread");
        return threads;
    }

    private static final class Recorder extends Entity {
        private final Set<Thread> tickThreads = ConcurrentHashMap.newKeySet();

        Recorder() {
            super(EntityType.ZOMBIE);
        }

        @Override
        public void tick(long time) {
            tickThreads.add(Thread.currentThread());
            super.tick(time);
        }
    }
}
