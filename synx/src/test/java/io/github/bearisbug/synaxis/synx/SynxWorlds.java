package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Builds the instances of the {@code :synx} integration tests on the {@link SynxTestMap}. */
final class SynxWorlds {
    static final SynxOptions WRITABLE = new SynxOptions(true, 256L << 20, 1, false);
    static final SynxOptions READ_ONLY = new SynxOptions(false, 256L << 20, 1, false);

    private SynxWorlds() {
    }

    /** The dimension type of the test map, y 0 to 256. */
    static RegistryKey<DimensionType> dimension(ServerProcess process) {
        final Key key = Key.key("synaxis:synx_test");
        final RegistryKey<DimensionType> existing = process.dimensionType().getKey(key);
        if (existing != null) return existing;
        return process.dimensionType().register(key, DimensionType.builder()
                .minY(0).height(SynxTestMap.HEIGHT).logicalHeight(SynxTestMap.HEIGHT).build());
    }

    static SynxChunkLoader loader(SynxOptions options, MemoryOverlay overlay) {
        final SynxTestMap map = SynxTestMap.get();
        return loader(map.buildDir, SynxTestMap.REGION, options, overlay);
    }

    static SynxChunkLoader loader(Path buildDir, SynxManifest.Bounds bounds,
                                  SynxOptions options, MemoryOverlay overlay) {
        return new SynxChunkLoader(mapped(buildDir, SynxTestMap.get().manifest, bounds), bounds, options, overlay, overlay);
    }

    /**
     * The region files a loader of {@code bounds} reads, mapped as they are: nothing is checked, so that a
     * test can damage a record first. The shard maps them through {@link SynxBuildVerifier#open}.
     */
    static VerifiedBuild mapped(Path buildDir, SynxManifest manifest, SynxManifest.Bounds bounds) {
        final int margin = SynxChunkLoader.VIEW_MARGIN;
        final SynxManifest.Bounds view = new SynxManifest.Bounds(bounds.minX() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxZ() + margin);
        final Map<Long, SynxRegion> regions = new HashMap<>();
        final Arena arena = Arena.ofShared();
        try {
            for (Path file : SynxBuildVerifier.regionFiles(buildDir, manifest, view)) {
                if (!Files.isRegularFile(file)) continue;
                final SynxRegion region = SynxRegion.open(file, arena);
                regions.put(VerifiedBuild.key(region.header().regionX(), region.header().regionZ()), region);
            }
        } catch (IOException | SynxException e) {
            throw new AssertionError(e);
        }
        return new VerifiedBuild(manifest, regions);
    }

    static Instance instance(ServerProcess process, SynxChunkLoader loader) {
        return process.instance().createInstanceContainer(dimension(process), loader);
    }

    /** Ticks until {@code done}, 20 s at most. */
    static void tickUntil(Env env, BooleanSupplier done, Runnable eachTick) {
        final long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!done.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            env.tick();
            eachTick.run();
            Thread.onSpinWait();
        }
    }

    /** The chunk coordinates in the body of a framed segment ①. */
    static int[] chunkOf(byte[] framed) {
        final byte[] body = SynxChunkPacket.body(framed);
        final NetworkBuffer buffer = NetworkBuffer.wrap(body, 0, body.length);
        buffer.read(NetworkBuffer.VAR_INT);
        return new int[]{buffer.read(NetworkBuffer.INT), buffer.read(NetworkBuffer.INT)};
    }

    /** A ticking handler that remembers where it ticked. */
    record Ticker(Key getKey, List<Long> ticks) implements BlockHandler {
        Ticker(String key) {
            this(Key.key(key), new CopyOnWriteArrayList<>());
        }

        @Override
        public boolean isTickable() {
            return true;
        }

        @Override
        public void tick(Tick tick) {
            ticks.add(((long) tick.getBlockPosition().blockX() << 32) | (tick.getBlockPosition().blockZ() & 0xFFFFFFFFL));
        }

        long ticksAt(int x, int z) {
            final long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
            return ticks.stream().filter(value -> value == key).count();
        }
    }
}
