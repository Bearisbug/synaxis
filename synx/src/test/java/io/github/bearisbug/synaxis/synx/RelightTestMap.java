package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.baker.Baker;
import io.github.bearisbug.synaxis.baker.GeneratorPreset;
import io.github.bearisbug.synaxis.baker.GeneratorSource;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.light.LightEngine;
import io.github.bearisbug.synaxis.registry.Registries;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * The small map of the runtime light tests ({@code docs/DESIGN-M3.md} §19, the {@code :synx}
 * integration entry of M3-D): 8 × 8 chunks, y 0 to 63, stone up to y 40 (every column stands on y
 * {@value #FLOOR}). Chunk (3,3) holds a cave of 5 × 5 × 4 blocks with a shaft of one block up to the
 * surface, whose topmost stone caps it. Baked by the real {@link Baker} once per JVM into a temporary
 * directory that goes when the JVM ends.
 */
final class RelightTestMap {
    static final Registries REGISTRIES = Registries.vanilla();
    static final int HEIGHT = 64;
    /** The first air block above the stone. */
    static final int FLOOR = 40;
    static final SynxManifest.Bounds BOUNDS = new SynxManifest.Bounds(0, 0, 7, 7);
    /** The cave: x and z 50 to 54, y 30 to 33, in chunk (3,3). */
    static final int CAVE_MIN = 50, CAVE_MAX = 54, CAVE_BOTTOM = 30, CAVE_TOP = 33;
    /** The shaft at x = z = 52 from the cave up to y 38; y 39 is the stone that caps it. */
    static final int SHAFT = 52, SHAFT_CAP = FLOOR - 1;

    private static volatile @Nullable RelightTestMap map;

    final Path buildDir;
    final SynxManifest manifest;

    private RelightTestMap(Path buildDir, SynxManifest manifest) {
        this.buildDir = buildDir;
        this.manifest = manifest;
    }

    static RelightTestMap get() {
        RelightTestMap current = map;
        if (current != null) return current;
        synchronized (RelightTestMap.class) {
            current = map;
            if (current == null) map = current = bake();
            return current;
        }
    }

    private static RelightTestMap bake() {
        try {
            final Path root = Files.createTempDirectory("synx-relight-map");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> SynxTestMap.deleteTree(root)));
            final GeneratorSource source = new GeneratorSource(new GeneratorPreset(unit -> {
                unit.modifier().fillHeight(0, FLOOR, Block.STONE);
                final Point start = unit.absoluteStart();
                if (start.chunkX() != 3 || start.chunkZ() != 3) return;
                for (int x = CAVE_MIN; x <= CAVE_MAX; x++) {
                    for (int z = CAVE_MIN; z <= CAVE_MAX; z++) {
                        for (int y = CAVE_BOTTOM; y <= CAVE_TOP; y++) unit.modifier().setBlock(x, y, z, Block.AIR);
                    }
                }
                for (int y = CAVE_TOP + 1; y < SHAFT_CAP; y++) unit.modifier().setBlock(SHAFT, y, SHAFT, Block.AIR);
            }, 0, HEIGHT, BOUNDS), REGISTRIES);
            final Baker.Result result = Baker.bake(new Baker.Request("relight-test", source, root.resolve("maps"), 1,
                    root.resolve("logs"), REGISTRIES, _ -> null, Set.of(LightEngine.VERSION), 1));
            if (result.exitCode() != Baker.EXIT_PUBLISHED || result.directory() == null || result.manifest() == null) {
                throw new IllegalStateException("the relight test map did not bake: " + result.exitCode() + " " + result.message());
            }
            return new RelightTestMap(result.directory(), result.manifest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Segment ① of a chunk as the region file holds it. */
    byte[] packet(int chunkX, int chunkZ) {
        try (Arena arena = Arena.ofConfined()) {
            final SynxSlot slot = SynxRegion.open(SynxTestMap.regionFile(buildDir, chunkX, chunkZ), arena).slot(chunkX, chunkZ);
            if (!(slot instanceof SynxSlot.Present present)) throw new AssertionError(chunkX + "," + chunkZ + ": " + slot);
            return present.packet().toArray(ValueLayout.JAVA_BYTE);
        } catch (IOException | SynxException e) {
            throw new AssertionError(e);
        }
    }

    SynxSettings settings() {
        return new SynxSettings(manifest.protocolVersion(), Baker.COMPRESSION_THRESHOLD, Baker.COMPRESSION_LEVEL,
                manifest.world().minY(), manifest.world().height(), manifest.registryDigest(),
                manifest.lightEngineVersion(), manifest.bakerVersion(), false);
    }
}
