package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.baker.Baker;
import io.github.bearisbug.synaxis.baker.GeneratorPreset;
import io.github.bearisbug.synaxis.baker.GeneratorSource;
import io.github.bearisbug.synaxis.baker.LightVersions;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.generator.GenerationUnit;
import io.github.bearisbug.synaxis.instance.generator.UnitModifier;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.synx.SynxManifest;

import java.nio.file.Path;

/**
 * The light test map of M3-D ({@code docs/DESIGN-M3.md} §26 step 4), baked by {@code :baker}: map id
 * {@value #MAP_ID}, 32 × 32 chunks, y 0 to 255. Bedrock at y 0, stone to y 59, dirt to 62, grass at
 * {@value #GRASS}. Every chunk is one of five kinds by {@code (x + z) mod 5}: flat; a roof of stone
 * slabs at y 68 on four oak logs; a cave of 7 × 7 × 4 at y 30 to 33 with glowstone on its floor and a
 * shaft of one block up to the grass that caps it; a cluster of emitters among half transparent
 * blocks on the surface; a pond. Chunk (10,10) holds an air chamber at the bottom of the world with
 * glowstone in it, chunk (20,20) a stone pillar up to y 249 with glowstone and leaves on top. Players
 * spawn in the center chunk (16,16).
 */
public final class LightMapFixture {
    public static final String MAP_ID = "light";
    public static final SynxManifest.Bounds BOUNDS = new SynxManifest.Bounds(0, 0, 31, 31);
    public static final int HEIGHT = 256;
    /** The grass of the surface; players stand at y 64. */
    public static final int GRASS = 63;
    /** The room of a cave chunk, local x and z 6 to 12, y 30 to 33; the shaft rises at local 12, 12. */
    static final int ROOM_MIN = 6, ROOM_MAX = 12, ROOM_BOTTOM = 30, ROOM_TOP = 33, SHAFT = 12;

    private static final Block[] EMITTERS = {Block.GLOWSTONE, Block.TORCH, Block.LANTERN, Block.SEA_LANTERN,
            Block.JACK_O_LANTERN, Block.SHROOMLIGHT, Block.SOUL_TORCH, Block.REDSTONE_TORCH};
    private static final Block[] FILTERS = {Block.STONE_SLAB, Block.OAK_STAIRS, Block.SNOW.withProperty("layers", "2"),
            Block.RED_STAINED_GLASS, Block.GLASS, Block.OAK_LEAVES};

    private LightMapFixture() {
    }

    /**
     * Bakes the map under {@code mapsRoot}.
     *
     * @return the settings of a shard mounting it, simulation distance 4 and grace 100 as the shard host has
     */
    public static ShardSettings bake(Path mapsRoot, int threads) {
        final GeneratorSource source = new GeneratorSource(new GeneratorPreset(LightMapFixture::generate, 0, HEIGHT, BOUNDS),
                Registries.vanilla());
        final Baker.Result result = Baker.bake(new Baker.Request(MAP_ID, source, mapsRoot, threads, mapsRoot.resolve("logs"),
                Registries.vanilla(), _ -> null, LightVersions.verified(), 1));
        if (result.exitCode() != Baker.EXIT_PUBLISHED || result.directory() == null)
            throw new AssertionError("the light map did not bake: " + result);
        return new ShardSettings(mapsRoot.resolve(MAP_ID), result.directory().getFileName().toString(), 4, 100);
    }

    static void generate(GenerationUnit unit) {
        final UnitModifier blocks = unit.modifier();
        blocks.fillHeight(0, 1, Block.BEDROCK);
        blocks.fillHeight(1, 60, Block.STONE);
        blocks.fillHeight(60, GRASS, Block.DIRT);
        blocks.fillHeight(GRASS, GRASS + 1, Block.GRASS_BLOCK);
        final Point start = unit.absoluteStart();
        final int chunkX = start.chunkX(), chunkZ = start.chunkZ();
        final int x0 = chunkX * 16, z0 = chunkZ * 16;
        if (chunkX == 10 && chunkZ == 10) {
            for (int x = 4; x <= 6; x++) {
                for (int y = 1; y <= 3; y++) {
                    for (int z = 4; z <= 6; z++) blocks.setBlock(x0 + x, y, z0 + z, Block.AIR);
                }
            }
            blocks.setBlock(x0 + 5, 1, z0 + 5, Block.GLOWSTONE);
        } else if (chunkX == 20 && chunkZ == 20) {
            for (int y = GRASS + 1; y <= 249; y++) blocks.setBlock(x0 + 8, y, z0 + 8, Block.STONE);
            blocks.setBlock(x0 + 8, 250, z0 + 8, Block.GLOWSTONE);
            for (int x = 7; x <= 9; x++) {
                for (int z = 7; z <= 9; z++) blocks.setBlock(x0 + x, 251, z0 + z, Block.OAK_LEAVES);
            }
        }
        switch ((chunkX + chunkZ) % 5) {
            case 1 -> {
                for (int x = 4; x <= 11; x++) {
                    for (int z = 4; z <= 11; z++) blocks.setBlock(x0 + x, 68, z0 + z, Block.STONE_SLAB);
                }
                for (int[] corner : new int[][]{{4, 4}, {11, 4}, {4, 11}, {11, 11}}) {
                    for (int y = GRASS + 1; y <= 67; y++) blocks.setBlock(x0 + corner[0], y, z0 + corner[1], Block.OAK_LOG);
                }
            }
            case 2 -> {
                for (int x = ROOM_MIN; x <= ROOM_MAX; x++) {
                    for (int z = ROOM_MIN; z <= ROOM_MAX; z++) {
                        for (int y = ROOM_BOTTOM; y <= ROOM_TOP; y++) blocks.setBlock(x0 + x, y, z0 + z, Block.AIR);
                    }
                }
                blocks.setBlock(x0 + 9, ROOM_BOTTOM, z0 + 9, Block.GLOWSTONE);
                for (int y = ROOM_TOP + 1; y < GRASS; y++) blocks.setBlock(x0 + SHAFT, y, z0 + SHAFT, Block.AIR);
            }
            case 3 -> {
                int emitter = 0, filter = 0;
                for (int x = 4; x <= 11; x++) {
                    for (int z = 4; z <= 11; z++) {
                        final Block block = (x + z) % 2 == 0 ? EMITTERS[emitter++ % EMITTERS.length]
                                : FILTERS[filter++ % FILTERS.length];
                        blocks.setBlock(x0 + x, GRASS + 1, z0 + z, block);
                    }
                }
            }
            case 4 -> {
                for (int x = 2; x <= 13; x++) {
                    for (int z = 2; z <= 13; z++) {
                        blocks.setBlock(x0 + x, 60, z0 + z, Block.SAND);
                        for (int y = 61; y <= GRASS; y++) blocks.setBlock(x0 + x, y, z0 + z, Block.WATER);
                    }
                }
            }
            default -> {
            }
        }
    }
}
