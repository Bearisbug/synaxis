package io.github.bearisbug.synaxis.instance.generator;

import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.generator.GeneratorImpl.GenSection;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.world.DimensionType;
import io.github.bearisbug.synaxis.world.biome.Biome;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class BiomeIntegrationTest {

    private static int plainsId, badlandsId;

    @BeforeAll
    public static void prepareTest(Env env) {
        plainsId = env.process().biome().getId(Biome.PLAINS);
        badlandsId = env.process().biome().getId(Biome.BADLANDS);
    }

    @Test
    public void chunkBiomeSet(Env env) {
        final int minSection = -1;
        final int maxSection = 5;
        final int chunkX = 3;
        final int chunkZ = -2;
        final int sectionCount = maxSection - minSection;
        GenSection[] sections = new GenSection[sectionCount];
        Arrays.setAll(sections, _ -> new GenSection());
        var chunkUnits = GeneratorImpl.chunk(env.process().biome(), sections, chunkX, minSection, chunkZ);
        Generator generator = unit -> {
            var modifier = unit.modifier();
            modifier.setBiome(48, -16, -32, Biome.BADLANDS);
            modifier.setBiome(48 + 8, 0, -32, Biome.BADLANDS);
        };
        generator.generate(chunkUnits);

        // Reminder because I (matt) forgot: biome palettes are 4x4x4 sections, so x=2 is really x=8 in the chunk.
        assertEquals(badlandsId, sections[0].biomes().get(0, 0, 0));
        assertEquals(plainsId, sections[1].biomes().get(1, 0, 0));
        assertEquals(badlandsId, sections[1].biomes().get(2, 0, 0));
    }

    @Test
    public void chunkBiomeFill(Env env) {
        final int minSection = -1;
        final int maxSection = 5;
        final int chunkX = 3;
        final int chunkZ = -2;
        final int sectionCount = maxSection - minSection;
        GenSection[] sections = new GenSection[sectionCount];
        Arrays.setAll(sections, _ -> new GenSection());
        var chunkUnits = GeneratorImpl.chunk(env.process().biome(), sections, chunkX, minSection, chunkZ);
        Generator generator = chunk -> {
            var modifier = chunk.modifier();
            modifier.fillBiome(Biome.PLAINS);
        };
        generator.generate(chunkUnits);
        for (var section : sections) {
            section.biomes().getAll((_, _, _, value) ->
                    assertEquals(plainsId, value));
        }
    }

    // Above or below the world a chunk reads the biome of its top or bottom section, as getBlock reads air there
    @Test
    public void biomeOutsideTheWorldReadsTheNearestSection(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        final DimensionType dimension = instance.getCachedDimensionType();
        instance.setBiome(0, dimension.maxY() - 1, 0, Biome.DESERT);
        instance.setBiome(0, dimension.minY(), 0, Biome.BADLANDS);

        for (int y : new int[]{dimension.maxY(), dimension.maxY() + 16, Integer.MAX_VALUE}) {
            assertEquals(Biome.DESERT, instance.getBiome(0, y, 0), "y " + y);
        }
        for (int y : new int[]{dimension.minY() - 1, dimension.minY() - 16, Integer.MIN_VALUE}) {
            assertEquals(Biome.BADLANDS, instance.getBiome(0, y, 0), "y " + y);
        }
    }

    // A biome set above or below the world changes nothing, the cached chunk packet included
    @Test
    public void biomeSetOutsideTheWorldChangesNothing(Env env) {
        final Instance instance = env.createFlatInstance();
        final Chunk chunk = instance.loadChunk(0, 0).join();
        final DimensionType dimension = instance.getCachedDimensionType();
        final RegistryKey<Biome> top = instance.getBiome(0, dimension.maxY() - 1, 0);
        final RegistryKey<Biome> bottom = instance.getBiome(0, dimension.minY(), 0);
        final CachedPacket packet = (CachedPacket) chunk.getFullDataPacket();
        assertNotNull(packet.body(ConnectionState.PLAY, env.process().registries(), 0));

        instance.setBiome(0, dimension.maxY() + 16, 0, Biome.DESERT);
        instance.setBiome(0, dimension.minY() - 16, 0, Biome.DESERT);

        assertTrue(packet.isValid(), "the chunk packet is still cached");
        assertEquals(top, instance.getBiome(0, dimension.maxY() - 1, 0));
        assertEquals(bottom, instance.getBiome(0, dimension.minY(), 0));
    }
}
