package io.github.bearisbug.synaxis.app.shard;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host keys of a map shard ({@code docs/DESIGN-M3.md} {@code API-M3-006}): read through a lookup
 * rather than the JVM properties, which every test of this JVM shares.
 */
public class ShardSettingsTest {

    private static ShardSettings read(Map<String, String> properties) {
        return ShardSettings.from(properties::get);
    }

    @Test
    public void withoutTheMapKeysNoMapIsMountedAndTheOtherTwoAreNotRead() {
        final ShardSettings settings = read(Map.of("synaxis.simulation-distance", "not read",
                "synaxis.chunk-unload-grace-ticks", "0"));
        assertFalse(settings.mountsMap());
        assertThrows(IllegalStateException.class, settings::buildDir);
    }

    @Test
    public void aMountedMapDefaultsToSimulationDistanceFourAndGraceHundred() {
        final ShardSettings settings = read(Map.of("synaxis.map.root", "/maps/world", "synaxis.map.build",
                "world-0123456789ab-p776"));
        assertTrue(settings.mountsMap());
        assertEquals(Path.of("/maps/world/world-0123456789ab-p776"), settings.buildDir());
        assertEquals(4, settings.simulationDistance());
        assertEquals(100, settings.chunkUnloadGraceTicks());
    }

    @Test
    public void theKeysOfAMountedMapAreRead() {
        final ShardSettings settings = read(Map.of("synaxis.map.root", "maps", "synaxis.map.build", "b",
                "synaxis.simulation-distance", "6", "synaxis.chunk-unload-grace-ticks", "-1"));
        assertEquals(6, settings.simulationDistance());
        assertEquals(-1, settings.chunkUnloadGraceTicks());
    }

    @Test
    public void oneMapKeyWithoutTheOtherIsRefused() {
        assertEquals("synaxis.map.root and synaxis.map.build mount a map together, only synaxis.map.build is given",
                assertThrows(IllegalArgumentException.class, () -> read(Map.of("synaxis.map.build", "b"))).getMessage());
        assertEquals("synaxis.map.root and synaxis.map.build mount a map together, only synaxis.map.root is given",
                assertThrows(IllegalArgumentException.class, () -> read(Map.of("synaxis.map.root", "maps"))).getMessage());
    }

    @Test
    public void aBuildIdThatIsAPathIsRefused() {
        for (String build : new String[]{"..", "a/b", "a\\b", " "}) {
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> read(Map.of("synaxis.map.root", "maps", "synaxis.map.build", build)));
            assertTrue(refused.getMessage().startsWith("Property 'synaxis.map.build'"), refused.getMessage());
        }
    }

    @Test
    public void aValueThatIsNotAnIntegerOrAGraceBelowOneIsRefused() {
        final Map<String, String> map = Map.of("synaxis.map.root", "maps", "synaxis.map.build", "b");
        assertEquals("Property 'synaxis.simulation-distance' is not an integer: four", assertThrows(
                IllegalArgumentException.class, () -> read(with(map, "synaxis.simulation-distance", "four"))).getMessage());
        assertEquals("Property 'synaxis.chunk-unload-grace-ticks' is neither -1 nor at least 1: 0", assertThrows(
                IllegalArgumentException.class, () -> read(with(map, "synaxis.chunk-unload-grace-ticks", "0"))).getMessage());
        assertEquals("Property 'synaxis.chunk-unload-grace-ticks' is neither -1 nor at least 1: -2", assertThrows(
                IllegalArgumentException.class, () -> read(with(map, "synaxis.chunk-unload-grace-ticks", "-2"))).getMessage());
    }

    private static Map<String, String> with(Map<String, String> map, String key, String value) {
        final HashMap<String, String> copy = new HashMap<>(map);
        copy.put(key, value);
        return copy;
    }
}
