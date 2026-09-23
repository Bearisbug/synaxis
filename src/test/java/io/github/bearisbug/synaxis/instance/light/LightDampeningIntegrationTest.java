package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.LightingChunk;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.generator.GenerationUnit;
import io.github.bearisbug.synaxis.instance.generator.Generator;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the light engine to the vanilla 26.2 rules for how much a block dims light passing into it
 * ({@code max(1, lightBlock)}), which blocks stop the sky column ({@code lightBlock != 0} or a
 * face-occluding edge), and which blocks use their shape for light at all
 * ({@code useShapeForLightOcclusion}). Every expected value here is also produced by the vanilla
 * engine in {@link LightParityIntegrationTest}.
 */
@EnvTest
public class LightDampeningIntegrationTest {

    private static Instance lit(Env env, Generator scene) {
        Instance instance = env.createEmptyInstance();
        instance.setChunkSupplier(LightingChunk::new);
        instance.setGenerator(unit -> {
            unit.modifier().fillHeight(0, 41, Block.STONE);
            scene.generate(unit);
        });
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                instance.loadChunk(x, z).join();
            }
        }
        LightingChunk.relight(instance, instance.getChunks());
        return instance;
    }

    /** Places a block only when it falls inside the unit being generated. */
    private static void put(GenerationUnit unit, int x, int y, int z, Block block) {
        var start = unit.absoluteStart();
        var end = unit.absoluteEnd();
        if (x < start.blockX() || x >= end.blockX() || z < start.blockZ() || z >= end.blockZ()) return;
        unit.modifier().setBlock(x, y, z, block);
    }

    private static int sky(Instance instance, int x, int y, int z) {
        Chunk chunk = Objects.requireNonNull(instance.getChunkAt(x, z));
        return chunk.getSectionAt(y).skyLight().getLevel(x & 15, y & 15, z & 15);
    }

    private static int block(Instance instance, int x, int y, int z) {
        Chunk chunk = Objects.requireNonNull(instance.getChunkAt(x, z));
        return chunk.getSectionAt(y).blockLight().getLevel(x & 15, y & 15, z & 15);
    }

    /**
     * A roof of a lightBlock==1 block dims the sky by one per block from the roof down: 14 in the roof,
     * 13 right under it, 9 at five blocks under it. None of these is on the old hand-written list.
     */
    @ParameterizedTest
    @ValueSource(strings = {"minecraft:mangrove_leaves", "minecraft:pale_oak_leaves", "minecraft:powder_snow",
            "minecraft:white_shulker_box", "minecraft:kelp_plant"})
    public void skyThroughDampeningRoof(String roof, Env env) {
        Block roofBlock = Objects.requireNonNull(Block.fromKey(roof));
        Instance instance = lit(env, unit -> unit.modifier().fillHeight(50, 51, roofBlock));

        assertEquals(1, roofBlock.lightBlocked());
        assertEquals(14, sky(instance, 8, 50, 8), roof + " roof cell");
        assertEquals(13, sky(instance, 8, 49, 8), roof + " one below the roof");
        assertEquals(9, sky(instance, 8, 45, 8), roof + " five below the roof");
    }

    /** Tinted glass has lightBlock 15 but an empty occlusion shape: it must stop light like stone. */
    @Test
    public void tintedGlassStopsSky(Env env) {
        Instance instance = lit(env, unit -> unit.modifier().fillHeight(50, 51, Block.TINTED_GLASS));

        assertEquals(0, sky(instance, 8, 50, 8));
        assertEquals(0, sky(instance, 8, 45, 8));
    }

    /** Light spreading sideways into tinted glass loses 15, not 1. */
    @Test
    public void tintedGlassStopsBlockLight(Env env) {
        Instance instance = lit(env, unit -> {
            put(unit, 8, 45, 8, Block.GLOWSTONE);
            put(unit, 9, 45, 8, Block.TINTED_GLASS);
        });

        assertEquals(15, block(instance, 8, 45, 8));
        assertEquals(14, block(instance, 7, 45, 8));
        assertEquals(0, block(instance, 9, 45, 8));
    }

    /**
     * Carpets, bottom slabs and enchanting tables have a full bottom face and nothing at the top. Vanilla
     * lets the sky column run into them at full strength: carpets do not use their shape for light at all,
     * and for slabs and enchanting tables only the edge between two blocks counts, not the block alone.
     */
    @ParameterizedTest
    @ValueSource(strings = {"minecraft:white_carpet", "minecraft:oak_slab", "minecraft:enchanting_table",
            "minecraft:snow"})
    public void skyReachesIntoLowBlock(String low, Env env) {
        Block lowBlock = Objects.requireNonNull(Block.fromKey(low));
        Instance instance = lit(env, unit -> put(unit, 8, 41, 8, lowBlock));

        assertEquals(15, sky(instance, 8, 41, 8), low);
    }

    /** A top slab over air closes the column at its top face, so the slab cell only gets light from the side. */
    @Test
    public void topSlabRoof(Env env) {
        Block topSlab = Block.OAK_SLAB.withProperty("type", "top");
        Instance instance = lit(env, unit -> unit.modifier().fillHeight(50, 51, topSlab));

        assertEquals(0, sky(instance, 8, 50, 8));
        assertEquals(0, sky(instance, 8, 49, 8));
    }
}
