package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.Viewable;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PacketViewableUtils#prepareViewablePacket} runs on the dispatcher threads while
 * {@link PacketViewableUtils#flush()} walks the same storages in parallel, so the storage map
 * has to tolerate concurrent lookups and traversals.
 * <p>
 * The fixture process disables viewable packets, so the test drives its own instance with them on.
 */
@EnvTest
public class PacketViewableUtilsConcurrencyIntegrationTest {

    private static final int VIEWABLES = 128;
    private static final int ROUNDS = 50;

    @Test
    public void concurrentPrepareAndFlushDoNotCorruptTheStorageMap(Env env) throws InterruptedException {
        assertNotNull(env.process().registries());
        final PacketViewableUtils viewablePackets = new PacketViewableUtils(env.process(), true,
                env.process().config().pooledBufferSize());
        final ServerPacket packet = new PluginMessagePacket("ch", new byte[8]);
        final List<CountingViewable> viewables = new ArrayList<>(VIEWABLES);
        for (int i = 0; i < VIEWABLES; i++) viewables.add(new CountingViewable());

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean writing = new AtomicBoolean(true);
        final List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            threads.add(Thread.ofPlatform().unstarted(() -> {
                try {
                    for (int round = 0; round < ROUNDS; round++) {
                        for (CountingViewable viewable : viewables) {
                            viewablePackets.prepareViewablePacket(viewable, packet, null);
                        }
                        // Short lived viewables leave stale weak entries behind
                        viewablePackets.prepareViewablePacket(new CountingViewable(), packet, null);
                        if (round % 25 == 0) System.gc();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }));
        }
        final Thread flusher = Thread.ofPlatform().unstarted(() -> {
            try {
                while (writing.get()) viewablePackets.flush();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        threads.forEach(Thread::start);
        flusher.start();
        for (Thread thread : threads) thread.join();
        writing.set(false);
        flusher.join();
        viewablePackets.flush();

        assertNull(failure.get(), () -> "storage map access failed: " + failure.get());
        for (CountingViewable viewable : viewables) {
            assertTrue(viewable.processed.get() > 0, "every registered storage must be flushed");
        }
    }

    private static final class CountingViewable implements Viewable {
        private final AtomicInteger processed = new AtomicInteger();

        @Override
        public boolean addViewer(Player player) {
            return false;
        }

        @Override
        public boolean removeViewer(Player player) {
            return false;
        }

        @Override
        public Set<? extends Player> getViewers() {
            processed.incrementAndGet();
            return Set.of();
        }
    }
}
