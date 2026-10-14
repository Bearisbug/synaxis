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

    /** §9 远景地址根: empty by default, which announces no far view. */
    @Test
    public void theFarViewUrlRootIsEmptyUnlessGiven() {
        final Map<String, String> map = Map.of("synaxis.map.root", "maps", "synaxis.map.build", "b");
        assertEquals("", read(map).farviewUrlRoot());
        assertEquals("", read(with(map, "synaxis.farview.url-root", "")).farviewUrlRoot());
        assertEquals("", new ShardSettings(Path.of("maps"), "b", 4, 100).farviewUrlRoot());
        assertEquals("http://cdn.test/synaxis",
                read(with(map, "synaxis.farview.url-root", "http://cdn.test/synaxis")).farviewUrlRoot());
        assertEquals("https://127.0.0.1:25751",
                read(with(map, "synaxis.farview.url-root", "https://127.0.0.1:25751")).farviewUrlRoot());
    }

    /** §9 远景地址根: read without the whitespace around it, which {@code synaxis.properties} keeps in the value. */
    @Test
    public void theFarViewUrlRootIsReadWithoutTheWhitespaceAroundIt() {
        final Map<String, String> map = Map.of("synaxis.map.root", "maps", "synaxis.map.build", "b");
        assertEquals("https://cdn.test/synaxis",
                read(with(map, "synaxis.farview.url-root", " https://cdn.test/synaxis \t")).farviewUrlRoot());
        assertEquals("", read(with(map, "synaxis.farview.url-root", "  ")).farviewUrlRoot());
        assertEquals("synaxis.farview.url-root must be an http(s) URL without a trailing slash: https://cdn.test/synaxis/",
                assertThrows(IllegalArgumentException.class,
                        () -> read(with(map, "synaxis.farview.url-root", "https://cdn.test/synaxis/ "))).getMessage());
    }

    /**
     * §9 远景地址根: an http(s) URL without a trailing slash that every client can request, a
     * {@code java.net.URI} with a host and without a query or fragment; anything else refuses the start.
     */
    @Test
    public void aFarViewUrlRootThatIsNotAnHttpUrlWithoutATrailingSlashIsRefused() {
        final Map<String, String> map = Map.of("synaxis.map.root", "maps", "synaxis.map.build", "b");
        for (String root : new String[]{"cdn.test/synaxis", "https://cdn.test/synaxis/", "ftp://cdn.test", "HTTP://cdn.test",
                "http://", "https://cdn.test/a b", "http://farview_cdn:8080", "https://cdn.test?x", "https://cdn.test/synaxis#x"}) {
            assertEquals("synaxis.farview.url-root must be an http(s) URL without a trailing slash: " + root,
                    assertThrows(IllegalArgumentException.class,
                            () -> read(with(map, "synaxis.farview.url-root", root))).getMessage(), root);
        }
    }

    private static Map<String, String> with(Map<String, String> map, String key, String value) {
        final HashMap<String, String> copy = new HashMap<>(map);
        copy.put(key, value);
        return copy;
    }
}
