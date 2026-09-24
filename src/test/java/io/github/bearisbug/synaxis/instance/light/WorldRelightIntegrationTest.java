package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.LightingChunk;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static io.github.bearisbug.synaxis.instance.BlockLightMergeIntegrationTest.assertLightInstance;

@EnvTest
public class WorldRelightIntegrationTest {
    private static Instance createLightingInstance(ServerProcess process) {
        var instance = process.instance().createInstanceContainer();
        instance.setGenerator(unit -> {
            unit.modifier().fillHeight(39, 40, Block.STONE);
            unit.subdivide().forEach(u -> u.modifier().setBlock(0, 10, 0, Block.GLOWSTONE));
            unit.modifier().fillHeight(50, 51, Block.STONE);
        });
        return instance;
    }

    @Test
    public void testBorderLava(Env env) {
        Instance instance = env.createFlatInstance();
        instance.setChunkSupplier(LightingChunk::new);
        instance.loadChunk(6, 16).join();
        instance.loadChunk(6, 15).join();

        instance.setBlock(106, 70, 248, Block.LAVA);
        instance.setBlock(106, 71, 249, Block.LAVA);

        Map<Vec, Integer> expectedLights = Map.ofEntries(
                entry(new Vec(105, 72, 256), 6)
        );

        LightingChunk.relight(instance, instance.getChunks());
        assertLightInstance(instance, expectedLights);
    }

    /** Chunks far apart are lit without room for everything between them. */
    @Test
    public void farApartChunksRelight(Env env) {
        Instance instance = env.createFlatInstance();
        instance.setChunkSupplier(LightingChunk::new);
        final Chunk west = instance.loadChunk(-40_000, -40_000).join();
        final Chunk east = instance.loadChunk(40_000, 40_000).join();

        assertEquals(Set.of(west, east), Set.copyOf(LightingChunk.relight(instance, List.of(west, east))));
        assertEquals(15, west.getSectionAt(100).skyLight().getLevel(0, 100 & 15, 0));
        assertEquals(15, east.getSectionAt(100).skyLight().getLevel(0, 100 & 15, 0));
    }

    @Test
    public void testBlockRemoval(Env env) {
        Instance instance = createLightingInstance(env.process());
        instance.setChunkSupplier(LightingChunk::new);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                instance.loadChunk(x, z).join();
            }
        }

        LightingChunk.relight(instance, instance.getChunks());

        var expectedLights = Map.ofEntries(
                entry(new Vec(-1, 40, 0), 12),
                entry(new Vec(-9, 40, 8), 0),
                entry(new Vec(-1, 40, -16), 12),
                entry(new Vec(-1, 37, 0), 3),
                entry(new Vec(-8, 37, -8), 0)
        );
        assertLightInstance(instance, expectedLights);
    }

    @Test
    public void testJackOLantern(Env env) {
        Instance instance = createLightingInstance(env.process());
        instance.setChunkSupplier(LightingChunk::new);

        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                instance.loadChunk(x, z).join();
            }
        }

        instance.setBlock(10, 60, 10, Block.JACK_O_LANTERN);
        LightingChunk.relight(instance, instance.getChunks());

        var expectedLights = Map.ofEntries(
                entry(new Vec(11, 60, 10), 14),
                entry(new Vec(10, 61, 10), 14),
                entry(new Vec(15, 60, 10), 10)
        );

        assertLightInstance(instance, expectedLights);
    }

    @Test
    public void testRedstoneLamp(Env env) {
        Instance instance = createLightingInstance(env.process());
        instance.setChunkSupplier(LightingChunk::new);

        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                instance.loadChunk(x, z).join();
            }
        }

        instance.setBlock(10, 60, 10, Block.REDSTONE_LAMP.withProperty("lit", "true"));
        LightingChunk.relight(instance, instance.getChunks());

        var expectedLights = Map.ofEntries(
                entry(new Vec(11, 60, 10), 14),
                entry(new Vec(10, 61, 10), 14),
                entry(new Vec(15, 60, 10), 10)
        );

        assertLightInstance(instance, expectedLights);
    }
}
