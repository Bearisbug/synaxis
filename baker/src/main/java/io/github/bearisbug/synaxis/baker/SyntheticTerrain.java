package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.generator.GenerationUnit;
import io.github.bearisbug.synaxis.instance.generator.Generator;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import org.jetbrains.annotations.Nullable;

/**
 * The baker's own test map, {@code --source generator:synthetic:<seed>}: 4,096 × 4,096 blocks (chunks 0 to 255
 * on both axes), 256 high from y 0, the natural terrain of the world benchmark ({@code LargeWorldBench}'s
 * {@code SyntheticTerrain}): value noise hills and mountains, bedrock, stone with ores and noise tunnels, dirt,
 * grass, sand beaches, water up to y 62 and an oak tree on about one column in 256. A pure function of the
 * position and the seed. Each section is written with {@code setAllRelative}, so the generator hands the baker
 * direct palettes, the case its palette canonicalisation exists for.
 */
public final class SyntheticTerrain implements Generator, BakeGeneratorProvider {
    public static final String PRESET = "synthetic";
    public static final int SIZE_CHUNKS = 256;
    public static final int HEIGHT = 256;

    private static final int SEA_LEVEL = 62;

    private final long seed;

    /** The {@link java.util.ServiceLoader} instance, which only hands out presets. */
    public SyntheticTerrain() {
        this(0);
    }

    public SyntheticTerrain(long seed) {
        this.seed = seed;
    }

    @Override
    public @Nullable GeneratorPreset preset(String id, long seed) {
        if (!id.equals(PRESET)) return null;
        return new GeneratorPreset(new SyntheticTerrain(seed), 0, HEIGHT,
                new SynxManifest.Bounds(0, 0, SIZE_CHUNKS - 1, SIZE_CHUNKS - 1));
    }

    /** The y of the top solid block of a column, before tunnels. */
    int surface(int x, int z) {
        final double hills = fbm(x / 256.0, z / 256.0, 5);
        final double mountains = Math.pow(Math.max(0, fbm(x / 640.0 + 17.3, z / 640.0 - 9.1, 4)), 1.5);
        return Math.min((int) Math.floor(64 + 22 * hills + 70 * mountains), HEIGHT - 16);
    }

    @Override
    public void generate(GenerationUnit unit) {
        final BlockVec start = unit.absoluteStart();
        final int chunkMinX = start.blockX();
        final int chunkMinZ = start.blockZ();
        final int[] surface = new int[256];
        int top = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final int s = surface(chunkMinX + x, chunkMinZ + z);
                surface[z << 4 | x] = s;
                top = Math.max(top, s);
            }
        }
        // Everything above this is air: the highest column, a tree on it, or the water surface
        final int filledTop = Math.max(top + 8, SEA_LEVEL + 1);
        for (GenerationUnit section : unit.subdivide()) {
            final int sectionMinY = section.absoluteStart().blockY();
            if (sectionMinY >= filledTop) continue;
            section.modifier().setAllRelative((x, y, z) ->
                    block(chunkMinX + x, sectionMinY + y, chunkMinZ + z, surface[z << 4 | x]));
        }
        // Trees stay two blocks off the chunk edge so the canopy never needs a neighbour
        for (int z = 2; z < 14; z++) {
            for (int x = 2; x < 14; x++) {
                final int s = surface[z << 4 | x];
                if (s <= SEA_LEVEL + 1) continue;
                if ((hash(chunkMinX + x, 1, chunkMinZ + z) & 255) != 0) continue;
                placeTree(unit, chunkMinX + x, s + 1, chunkMinZ + z);
            }
        }
    }

    private Block block(int x, int y, int z, int surface) {
        if (y > surface) return y <= SEA_LEVEL ? Block.WATER : Block.AIR;
        if (y == 0 || (y < 5 && Math.floorMod(hash(x, y, z), 5) >= y)) return Block.BEDROCK;
        if (y == surface) {
            if (surface < SEA_LEVEL) return Math.floorMod(hash(x, 3, z), 3) == 0 ? Block.GRAVEL : Block.SAND;
            return surface <= SEA_LEVEL + 2 ? Block.SAND : Block.GRASS_BLOCK;
        }
        if (y > surface - 4) return surface <= SEA_LEVEL + 2 ? Block.SAND : Block.DIRT;
        if (y > 5 && y < surface - 6 && Math.abs(noise3(x / 24.0, y / 12.0, z / 24.0)) < 0.06) return Block.AIR;
        final int ore = hash(x, y, z) & 1023;
        if (ore < 12) {
            return switch (ore % 3) {
                case 0 -> Block.COAL_ORE;
                case 1 -> Block.IRON_ORE;
                default -> Block.COPPER_ORE;
            };
        }
        return Block.STONE;
    }

    private static void placeTree(GenerationUnit unit, int x, int baseY, int z) {
        for (int dy = 3; dy <= 6; dy++) {
            final int radius = dy <= 4 ? 2 : 1;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius && radius == 2) continue;
                    unit.modifier().setBlock(x + dx, baseY + dy, z + dz, Block.OAK_LEAVES);
                }
            }
        }
        for (int dy = 0; dy < 5; dy++) unit.modifier().setBlock(x, baseY + dy, z, Block.OAK_LOG);
    }

    private int hash(int x, int y, int z) {
        long h = seed ^ 0x5EED_2026_09L;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= y * 0xC2B2AE3D27D4EB4FL;
        h ^= z * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return (int) (h ^ (h >>> 31));
    }

    /** A lattice value in [-1, 1). */
    private double lattice(int x, int y, int z) {
        return (hash(x, y, z) >>> 8) / (double) (1 << 23) - 1.0;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private double noise2(double x, double z) {
        final int x0 = (int) Math.floor(x);
        final int z0 = (int) Math.floor(z);
        final double u = smooth(x - x0);
        final double v = smooth(z - z0);
        return lerp(lerp(lattice(x0, 0, z0), lattice(x0 + 1, 0, z0), u),
                lerp(lattice(x0, 0, z0 + 1), lattice(x0 + 1, 0, z0 + 1), u), v);
    }

    private double noise3(double x, double y, double z) {
        final int x0 = (int) Math.floor(x);
        final int y0 = (int) Math.floor(y);
        final int z0 = (int) Math.floor(z);
        final double u = smooth(x - x0);
        final double v = smooth(y - y0);
        final double w = smooth(z - z0);
        final double bottom = lerp(lerp(lattice(x0, y0, z0), lattice(x0 + 1, y0, z0), u),
                lerp(lattice(x0, y0, z0 + 1), lattice(x0 + 1, y0, z0 + 1), u), w);
        final double top = lerp(lerp(lattice(x0, y0 + 1, z0), lattice(x0 + 1, y0 + 1, z0), u),
                lerp(lattice(x0, y0 + 1, z0 + 1), lattice(x0 + 1, y0 + 1, z0 + 1), u), w);
        return lerp(bottom, top, v);
    }

    private double fbm(double x, double z, int octaves) {
        double sum = 0;
        double amplitude = 1;
        double norm = 0;
        for (int i = 0; i < octaves; i++) {
            sum += amplitude * noise2(x * (1 << i), z * (1 << i));
            norm += amplitude;
            amplitude *= 0.5;
        }
        return sum / norm;
    }
}
