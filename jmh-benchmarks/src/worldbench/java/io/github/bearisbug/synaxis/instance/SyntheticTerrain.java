package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.generator.GenerationUnit;
import io.github.bearisbug.synaxis.instance.generator.Generator;

/**
 * Deterministic synthetic terrain for {@link LargeWorldBench}: a pure function of the block
 * position, so every round and every JVM builds the same world.
 * <p>
 * Three kinds:
 * <ul>
 *   <li>{@link Kind#NATURAL}: two-scale value-noise height field (hills plus mountains), bedrock,
 *   deepslate below y=0, stone, three dirt layers, grass, sand beaches, water up to sea level,
 *   ores in about 1.2% of the stone, noise tunnels, and about one oak tree per two chunks.</li>
 *   <li>{@link Kind#SOLID}: the same surface without ores and caves, what a hand-built map with an
 *   untouched underground looks like.</li>
 *   <li>{@link Kind#FLAT40}: the M2 RTS map shape, stone up to y=40 with a 3-block pillar on one
 *   column in 64 (MapTerrain).</li>
 * </ul>
 * Worlds at least 256 high keep the vanilla vertical layout (sea level 62, surface roughly
 * 42 to 156). A 64-high world squeezes the same field into sea level 28, surface 16 to 55.
 */
final class SyntheticTerrain implements Generator {

    enum Kind { NATURAL, SOLID, FLAT40 }

    private static final long SEED = 0x5EED_2026_09L;
    private static final int FLAT_GROUND = 40;

    private final Kind kind;
    private final int minY;
    private final int maxY;
    private final boolean tall;
    private final int seaLevel;

    SyntheticTerrain(Kind kind, int minY, int height) {
        this.kind = kind;
        this.minY = minY;
        this.maxY = minY + height;
        this.tall = height >= 256;
        this.seaLevel = tall ? 62 : 28;
    }

    /** The surface height (y of the top solid block) of a column, before caves. */
    int surface(int x, int z) {
        if (kind == Kind.FLAT40) return FLAT_GROUND - 1;
        final double hills = fbm(x / 256.0, z / 256.0, 5);
        final double mountains = Math.pow(Math.max(0, fbm(x / 640.0 + 17.3, z / 640.0 - 9.1, 4)), 1.5);
        final double y = tall ? 64 + 22 * hills + 70 * mountains : 26 + 10 * hills + 29 * mountains;
        return Math.min((int) Math.floor(y), tall ? maxY - 16 : maxY - 9);
    }

    @Override
    public void generate(GenerationUnit unit) {
        final BlockVec start = unit.absoluteStart();
        final int chunkMinX = start.blockX();
        final int chunkMinZ = start.blockZ();
        final int[] surface = new int[256];
        int top = minY;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final int s = surface(chunkMinX + x, chunkMinZ + z);
                surface[z << 4 | x] = s;
                top = Math.max(top, s);
            }
        }
        // Everything above this is air: the highest column, a tree on it, or the water surface
        final int filledTop = Math.max(top + 8, seaLevel + 1);

        for (GenerationUnit section : unit.subdivide()) {
            final BlockVec sectionStart = section.absoluteStart();
            final int sectionMinY = sectionStart.blockY();
            if (sectionMinY >= filledTop) continue;
            section.modifier().setAllRelative((x, y, z) ->
                    block(chunkMinX + x, sectionMinY + y, chunkMinZ + z, surface[z << 4 | x]));
        }

        if (kind == Kind.FLAT40) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (Math.floorMod(hash(chunkMinX + x, 7, chunkMinZ + z), 64) != 0) continue;
                    for (int y = FLAT_GROUND; y < FLAT_GROUND + 3; y++) {
                        unit.modifier().setBlock(chunkMinX + x, y, chunkMinZ + z, Block.STONE);
                    }
                }
            }
            return;
        }
        // Trees stay two blocks off the chunk edge so the canopy never needs a neighbour
        for (int z = 2; z < 14; z++) {
            for (int x = 2; x < 14; x++) {
                final int s = surface[z << 4 | x];
                if (s <= seaLevel + 1) continue;
                if ((hash(chunkMinX + x, 1, chunkMinZ + z) & 255) != 0) continue;
                placeTree(unit, chunkMinX + x, s + 1, chunkMinZ + z);
            }
        }
    }

    private Block block(int x, int y, int z, int surface) {
        if (kind == Kind.FLAT40) {
            if (y == minY) return Block.BEDROCK;
            return y < FLAT_GROUND ? Block.STONE : Block.AIR;
        }
        if (y > surface) return y <= seaLevel ? Block.WATER : Block.AIR;
        if (y == minY || (y < minY + 5 && Math.floorMod(hash(x, y, z), 5) >= y - minY)) return Block.BEDROCK;
        final boolean underwater = surface < seaLevel;
        if (y == surface) {
            if (underwater) return Math.floorMod(hash(x, 3, z), 3) == 0 ? Block.GRAVEL : Block.SAND;
            return surface <= seaLevel + 2 ? Block.SAND : Block.GRASS_BLOCK;
        }
        if (y > surface - 4) return surface <= seaLevel + 2 ? Block.SAND : Block.DIRT;
        if (kind == Kind.NATURAL) {
            if (y > minY + 5 && y < surface - 6 && Math.abs(noise3(x / 24.0, y / 12.0, z / 24.0)) < 0.06) {
                return Block.AIR;
            }
            final int ore = hash(x, y, z) & 1023;
            if (ore < 12) {
                if (y < 0) return Block.DEEPSLATE_IRON_ORE;
                return switch (ore % 3) {
                    case 0 -> Block.COAL_ORE;
                    case 1 -> Block.IRON_ORE;
                    default -> Block.COPPER_ORE;
                };
            }
        }
        if (y < 0 && y < Math.floorMod(hash(x, y, z) >>> 10, 8) - 8) return Block.DEEPSLATE;
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

    private static int hash(int x, int y, int z) {
        long h = SEED;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= y * 0xC2B2AE3D27D4EB4FL;
        h ^= z * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return (int) (h ^ (h >>> 31));
    }

    /** A lattice value in [-1, 1). */
    private static double lattice(int x, int y, int z) {
        return (hash(x, y, z) >>> 8) / (double) (1 << 23) - 1.0;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double noise2(double x, double z) {
        final int x0 = (int) Math.floor(x);
        final int z0 = (int) Math.floor(z);
        final double u = smooth(x - x0);
        final double v = smooth(z - z0);
        return lerp(lerp(lattice(x0, 0, z0), lattice(x0 + 1, 0, z0), u),
                lerp(lattice(x0, 0, z0 + 1), lattice(x0 + 1, 0, z0 + 1), u), v);
    }

    private static double noise3(double x, double y, double z) {
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

    private static double fbm(double x, double z, int octaves) {
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
