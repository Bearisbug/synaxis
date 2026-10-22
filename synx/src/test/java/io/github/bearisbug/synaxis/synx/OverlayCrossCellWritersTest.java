package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.testing.Env;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Two grid tick threads change blocks on both sides of a chunk border, each on its own side and across into the
 * other's chunk, every tick, while the runtime light recomputes and installs and the overlay store writes at the
 * end of every tick ({@code docs/DESIGN-M3.md} §16). 4 tick threads on a grid of 2 by 2 chunk cells: chunk x = 1 is
 * in cell (0, 5), chunk x = 2 in cell (1, 5). After a final flush the reopened store has, for both chunks, the
 * generation and the blocks the instance has, under the grid of 4 threads in strict mode as well.
 */
public class OverlayCrossCellWritersTest {
    private static final int Y = SynxTestMap.FEATURE_Y;
    private static final int CHUNK_Z = 10;
    private static final int WEST_X = 31, EAST_X = 32;
    private static final Block[] BLOCKS = {Block.GOLD_BLOCK, Block.DIAMOND_BLOCK, Block.EMERALD_BLOCK, Block.IRON_BLOCK};
    private static final int TICKS = 120;

    private Env env;

    @AfterEach
    void tearDown() {
        if (env != null) env.close();
    }

    /** An entity whose every tick changes two blocks: one in its own chunk, one across the border in the other. */
    private static final class Writer extends Entity {
        private final Instance world;
        private final int ownX, otherX, salt;
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        volatile boolean writing = true;
        private int count;

        Writer(Instance instance, int ownX, int otherX, int salt) {
            super(EntityType.MARKER);
            this.world = instance;
            this.ownX = ownX;
            this.otherX = otherX;
            this.salt = salt;
            setNoGravity(true);
        }

        @Override
        public void update(long time) {
            if (!writing) return;
            threads.add(Thread.currentThread().getName());
            final int z = CHUNK_Z * 16 + (count & 15);
            world.setBlock(ownX, Y, z, BLOCKS[(count + salt) & 3]);
            world.setBlock(otherX, Y + 1, z, BLOCKS[(count + salt + 1) & 3]);
            count++;
        }
    }

    @RepeatedTest(5)
    public void twoTickThreadsWritingAcrossAChunkBorderLeaveTheOverlayEqualToTheWorld(@TempDir Path root) throws Exception {
        env = Env.create(settings -> settings.dispatcherThreads(4)
                .threadProvider(ServerConfig.ThreadProviderKind.GRID)
                .threadGridExponent(1));
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        env.process().exception().setExceptionHandler(errors::add);
        final SynxTestMap map = SynxTestMap.get();
        final OverlayFixture.Clock clock = new OverlayFixture.Clock();
        final OverlayStore store = OverlayStore.open(root, map.manifest, map.settings(), new OverlayOptions(16, 10),
                OverlayFixture.tuning(2, clock, new OverlayFixture.CountingDisk()));
        final SynxChunkLoader loader = new SynxChunkLoader(SynxWorlds.mapped(map.buildDir, map.manifest, SynxTestMap.REGION),
                SynxTestMap.REGION, SynxWorlds.WRITABLE, store, store);
        final InstanceContainer instance = (InstanceContainer) SynxWorlds.instance(env.process(), loader);
        final SynxRelight relight = loader.startRelight(instance);
        final SynxChunk west = (SynxChunk) instance.loadChunk(1, CHUNK_Z).join();
        final SynxChunk east = (SynxChunk) instance.loadChunk(2, CHUNK_Z).join();
        final Writer westWriter = new Writer(instance, WEST_X, EAST_X, 0);
        final Writer eastWriter = new Writer(instance, EAST_X, WEST_X, 2);
        westWriter.setInstance(instance, new Pos(WEST_X + 0.5, Y + 4, CHUNK_Z * 16 + 8.5)).join();
        eastWriter.setInstance(instance, new Pos(EAST_X + 0.5, Y + 4, CHUNK_Z * 16 + 8.5)).join();

        for (int tick = 1; tick <= TICKS; tick++) {
            env.tick();
            relight.installPhase();
            store.tickEnd();
            // Every 20 ticks both chunks are past their flush interval: the write phase writes them while they change
            if (tick % 20 == 0) clock.advance(Duration.ofSeconds(11));
        }
        westWriter.writing = false;
        eastWriter.writing = false;
        SynxWorlds.tickUntil(env, () -> relight.stats().pending() == 0, () -> {
            relight.installPhase();
            store.tickEnd();
        });
        for (int i = 0; i < 5; i++) {
            env.tick();
            relight.installPhase();
            store.tickEnd();
        }
        store.finalFlush(Duration.ofSeconds(2), false).get(5, TimeUnit.SECONDS);

        assertEquals(1, westWriter.threads.size(), "the west writer on one tick thread: " + westWriter.threads);
        assertEquals(1, eastWriter.threads.size(), "the east writer on one tick thread: " + eastWriter.threads);
        assertNotEquals(westWriter.threads, eastWriter.threads, "on two tick threads: " + westWriter.threads
                + " " + eastWriter.threads);
        final OverlayStore reopened = OverlayStore.open(root, map.manifest, map.settings(), new OverlayOptions(16, 10),
                OverlayFixture.tuning(1, new OverlayFixture.Clock(), new OverlayFixture.CountingDisk()));
        final List<String> differences = new ArrayList<>();
        for (SynxChunk chunk : List.of(west, east)) {
            final OverlayHit hit = reopened.find(chunk.chunkX(), chunk.chunkZ());
            assertNotNull(hit, "chunk " + chunk.chunkX() + " has a record");
            if (hit.generation() != chunk.generation()) {
                differences.add("chunk " + chunk.chunkX() + " generation " + hit.generation() + " on disk, "
                        + chunk.generation() + " in the world");
            }
            final int baseX = chunk.chunkX() * 16;
            for (int x = baseX; x < baseX + 16; x++) {
                for (int y = Y; y <= Y + 1; y++) {
                    for (int z = CHUNK_Z * 16; z < CHUNK_Z * 16 + 16; z++) {
                        final int world = instance.getBlock(x, y, z).stateId();
                        final int disk = stateIn(hit, x, y, z);
                        if (world != disk) differences.add(x + "," + y + "," + z + " world " + world + " disk " + disk);
                    }
                }
            }
        }
        assertEquals(List.of(), differences, "the overlay against the world after the final flush");
        assertEquals(List.of(), errors, "exceptions on the tick threads or the write phase");
    }

    private static int stateIn(OverlayHit hit, int x, int y, int z) {
        final SynxPacketDecoder.Sections sections = SynxPacketDecoder.sections(hit.record().packet(),
                SynxTestMap.REGISTRIES, SynxTestMap.HEIGHT / 16);
        return sections.blocks()[y >> 4].get(x & 15, y & 15, z & 15);
    }
}
