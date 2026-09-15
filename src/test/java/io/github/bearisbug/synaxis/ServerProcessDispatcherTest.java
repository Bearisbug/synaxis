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

    private static ServerProcess start(int dispatcherThreads) {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder()
                .insideTest(true)
                .dispatcherThreads(dispatcherThreads)
                .build());
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
