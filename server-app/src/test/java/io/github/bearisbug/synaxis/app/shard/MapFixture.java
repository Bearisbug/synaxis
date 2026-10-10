package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.baker.Baker;
import io.github.bearisbug.synaxis.baker.GeneratorPreset;
import io.github.bearisbug.synaxis.baker.GeneratorSource;
import io.github.bearisbug.synaxis.baker.LightVersions;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.synx.SynxManifest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;

/**
 * A map release for the shard tests, baked by {@code :baker}: 16 × 16 chunks, 64 blocks high, stone up
 * to y 40, so every column stands at {@code surface_y} 40.
 */
public final class MapFixture {
    public static final String MAP_ID = "fixture";
    public static final int FLOOR = 40;
    public static final SynxManifest.Bounds BOUNDS = new SynxManifest.Bounds(0, 0, 15, 15);

    private MapFixture() {
    }

    /**
     * Bakes the release under {@code mapsRoot}.
     *
     * @return the settings of a shard mounting it
     */
    public static ShardSettings bake(Path mapsRoot) {
        final GeneratorSource source = new GeneratorSource(new GeneratorPreset(
                unit -> unit.modifier().fillHeight(0, FLOOR, Block.STONE), 0, 64, BOUNDS), Registries.vanilla());
        final Baker.Result result = Baker.bake(new Baker.Request(MAP_ID, source, mapsRoot, 1, mapsRoot.resolve("logs"),
                Registries.vanilla(), _ -> null, LightVersions.verified(), 1));
        if (result.exitCode() != Baker.EXIT_PUBLISHED || result.directory() == null)
            throw new AssertionError("the fixture map did not bake: " + result);
        return new ShardSettings(mapsRoot.resolve(MAP_ID), result.directory().getFileName().toString(), 4, 100);
    }

    /**
     * Copies a release, to damage the copy: directories 0755 and files 0644, as the baker publishes them,
     * whatever the umask of the test JVM, or the shard refuses the copy ({@code docs/DESIGN-M3.md}
     * {@code API-M3-002} 文件信任).
     */
    public static ShardSettings copy(ShardSettings release, Path mapsRoot) {
        final Path target = mapsRoot.resolve(MAP_ID).resolve(release.mapBuild());
        try (Stream<Path> files = Files.walk(release.buildDir())) {
            Files.createDirectories(target.getParent());
            Files.setPosixFilePermissions(target.getParent(), PosixFilePermissions.fromString("rwxr-xr-x"));
            for (Path file : files.toList()) {
                final Path copy = Files.copy(file, target.resolve(release.buildDir().relativize(file).toString()));
                Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString(Files.isDirectory(copy)
                        ? "rwxr-xr-x" : "rw-r--r--"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ShardSettings(mapsRoot.resolve(MAP_ID), release.mapBuild(), release.simulationDistance(),
                release.chunkUnloadGraceTicks());
    }
}
