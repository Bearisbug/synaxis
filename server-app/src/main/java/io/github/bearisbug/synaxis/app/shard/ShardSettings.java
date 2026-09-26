package io.github.bearisbug.synaxis.app.shard;

import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

/**
 * The four host keys of a map shard ({@code docs/DESIGN-M3.md} {@code API-M3-006}, §9): which map
 * release it mounts, and the simulation distance and unload grace period of the map instance. Read in
 * step (1) of {@code Main}, before anything touches the disk. The last two are only read when a map
 * is mounted; their range against the view distance is checked by {@link ShardWorld#prepare}.
 *
 * @param mapRoot               {@code <maps_root>/<map_id>}, null when no map is mounted
 * @param mapBuild              the {@code build_id} under it, null when no map is mounted
 * @param simulationDistance    the simulation distance of the map instance, in chunks
 * @param chunkUnloadGraceTicks the unload grace period of the map instance, -1 or at least 1
 */
public record ShardSettings(@Nullable Path mapRoot, @Nullable String mapBuild, int simulationDistance,
                            int chunkUnloadGraceTicks) {
    public static final String MAP_ROOT = "synaxis.map.root";
    public static final String MAP_BUILD = "synaxis.map.build";
    public static final String SIMULATION_DISTANCE = "synaxis.simulation-distance";
    public static final String CHUNK_UNLOAD_GRACE_TICKS = "synaxis.chunk-unload-grace-ticks";
    static final int DEFAULT_SIMULATION_DISTANCE = 4;
    static final int DEFAULT_CHUNK_UNLOAD_GRACE_TICKS = 100;

    /**
     * Reads the keys from the system properties.
     *
     * @throws IllegalArgumentException naming the key: one of the two map keys without the other, a
     *                                  value that is not an integer, a grace period that is neither -1
     *                                  nor at least 1
     */
    public static ShardSettings fromSystemProperties() {
        return from(System::getProperty);
    }

    static ShardSettings from(UnaryOperator<String> properties) {
        final String root = properties.apply(MAP_ROOT);
        final String build = properties.apply(MAP_BUILD);
        if (root == null && build == null)
            return new ShardSettings(null, null, DEFAULT_SIMULATION_DISTANCE, DEFAULT_CHUNK_UNLOAD_GRACE_TICKS);
        if (root == null || build == null) {
            throw new IllegalArgumentException(MAP_ROOT + " and " + MAP_BUILD + " mount a map together, only "
                    + (root == null ? MAP_BUILD : MAP_ROOT) + " is given");
        }
        if (root.isBlank()) throw new IllegalArgumentException("Property '" + MAP_ROOT + "' is empty");
        if (build.isBlank()) throw new IllegalArgumentException("Property '" + MAP_BUILD + "' is empty");
        // A build_id names one directory under the map root, never a path out of it
        if (build.contains("/") || build.contains("\\") || build.equals(".") || build.equals("..")) {
            throw new IllegalArgumentException("Property '" + MAP_BUILD + "' is not a build_id: " + build);
        }
        final Path mapRoot;
        try {
            mapRoot = Path.of(root);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Property '" + MAP_ROOT + "' is not a path: " + root, e);
        }
        final int simulationDistance = intProperty(properties, SIMULATION_DISTANCE, DEFAULT_SIMULATION_DISTANCE);
        final int grace = intProperty(properties, CHUNK_UNLOAD_GRACE_TICKS, DEFAULT_CHUNK_UNLOAD_GRACE_TICKS);
        if (grace != -1 && grace < 1) {
            throw new IllegalArgumentException("Property '" + CHUNK_UNLOAD_GRACE_TICKS + "' is neither -1 nor at least 1: "
                    + grace);
        }
        return new ShardSettings(mapRoot, build, simulationDistance, grace);
    }

    /** Whether the shard mounts a map, the two map keys given. */
    public boolean mountsMap() {
        return mapRoot != null && mapBuild != null;
    }

    /**
     * The release directory {@code <maps_root>/<map_id>/<build_id>/}.
     *
     * @throws IllegalStateException without a map
     */
    public Path buildDir() {
        if (mapRoot == null || mapBuild == null) throw new IllegalStateException("no map is mounted");
        return mapRoot.resolve(mapBuild);
    }

    private static int intProperty(UnaryOperator<String> properties, String name, int defaultValue) {
        final String value = properties.apply(name);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property '" + name + "' is not an integer: " + value, e);
        }
    }
}
