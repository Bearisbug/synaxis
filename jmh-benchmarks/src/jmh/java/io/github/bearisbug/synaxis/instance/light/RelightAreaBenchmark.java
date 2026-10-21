package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * One runtime light recomputation ({@code SynxRelight}): {@link LightEngine#relightArea} over the 5×5 chunks
 * around a center, read through a view that answers the way the recomputation's snapshot does, from one
 * canonical block palette per section. The world is the {@code :synx} test map of {@code SynxRelightBacklogTest}:
 * the baker's synthetic terrain, seed 7, 256 high from y 0 (16 sections), cave air and void air at y 204 in the
 * corner of every chunk, and the test's change, glowstone at y 220 over the middle of a chunk, set in the center
 * and in the chunks the test changed before it (rows of increasing z, x increasing within a row).
 */
@Warmup(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Fork(2)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class RelightAreaBenchmark {
    private static final int CENTER_X = 16, CENTER_Z = 16;
    private static final int HEIGHT = 256;
    private static final int SECTIONS = HEIGHT / 16;
    private static final int OTHER_AIR_Y = 204, CHANGE_Y = 220;

    private SnapshotView view;

    /** The workspace of one recomputation thread, kept from one call to the next as {@code SynxRelight} keeps it. */
    @State(Scope.Thread)
    public static class Worker {
        final LightEngine.Workspace workspace = new LightEngine.Workspace();
    }

    @Setup
    public void setup() {
        view = new SnapshotView(CENTER_X, CENTER_Z, new Terrain(7));
    }

    @Benchmark
    public LightResult relightArea(Worker worker) {
        return LightEngine.relightArea(view, CENTER_X, CENTER_Z, worker.workspace);
    }

    /**
     * The 5×5 around a center as {@code SynxRelight}'s snapshot holds it: a palette per section, which a section read
     * copies out at once.
     */
    private static final class SnapshotView implements WorldView {
        private final int centerX, centerZ;
        private final Palette[][] chunks = new Palette[25][];

        SnapshotView(int centerX, int centerZ, Terrain terrain) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    final int chunkX = centerX + dx, chunkZ = centerZ + dz;
                    final int[] blocks = terrain.chunk(chunkX, chunkZ);
                    blocks[index(0, OTHER_AIR_Y, 0)] = Block.CAVE_AIR.stateId();
                    blocks[index(1, OTHER_AIR_Y, 0)] = Block.VOID_AIR.stateId();
                    if (chunkZ < centerZ || (chunkZ == centerZ && chunkX <= centerX)) {
                        blocks[index(8, CHANGE_Y, 8)] = Block.GLOWSTONE.stateId();
                    }
                    final Palette[] sections = new Palette[SECTIONS];
                    for (int s = 0; s < SECTIONS; s++) {
                        final int base = s << 12;
                        // What SynxChunkPacket#canonical makes of a section, which the snapshot decodes
                        final Palette palette = Palette.blocks();
                        palette.setAll((x, y, z) -> blocks[base | y << 8 | z << 4 | x]);
                        palette.optimize(Palette.Optimization.SIZE);
                        sections[s] = palette;
                    }
                    chunks[(dx + 2) * 5 + dz + 2] = sections;
                }
            }
        }

        private static int index(int x, int y, int z) {
            return y << 8 | z << 4 | x;
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int height() {
            return HEIGHT;
        }

        @Override
        public boolean hasChunk(int chunkX, int chunkZ) {
            return Math.abs(chunkX - centerX) <= 2 && Math.abs(chunkZ - centerZ) <= 2;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            final Palette section = chunks[((x >> 4) - centerX + 2) * 5 + (z >> 4) - centerZ + 2][y >> 4];
            return Objects.requireNonNullElse(Block.fromStateId(section.get(x & 15, y & 15, z & 15)), Block.AIR);
        }

        @Override
        public boolean section(int chunkX, int sectionMinY, int chunkZ, char[] ids) {
            final Palette section = chunks[(chunkX - centerX + 2) * 5 + chunkZ - centerZ + 2][sectionMinY >> 4];
            if (section.isEmpty()) return false;
            Arrays.fill(ids, (char) 0);
            final int states = Block.statesCount();
            section.getAllPresent((x, y, z, stateId) ->
                    ids[x | z << 4 | y << 8] = (char) (stateId >= 0 && stateId < states ? stateId : 0));
            return true;
        }
    }

    /**
     * The baker's {@code SyntheticTerrain} (natural kind, 256 high from y 0), a pure function of the position and
     * the seed.
     */
    private static final class Terrain {
        private static final int SEA_LEVEL = 62;
        private static final int TREE_ROLL_Y = 1, SEABED_ROLL_Y = 3;

        private final long seed;

        Terrain(long seed) {
            this.seed = seed;
        }

        /** The state ids of a chunk, {@code y << 8 | z << 4 | x}. */
        int[] chunk(int chunkX, int chunkZ) {
            final int minX = chunkX * 16, minZ = chunkZ * 16;
            final int[] blocks = new int[HEIGHT << 8];
            final int[] surface = new int[256];
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    final int s = surface(minX + x, minZ + z);
                    surface[z << 4 | x] = s;
                    for (int y = 0; y < HEIGHT; y++) {
                        blocks[y << 8 | z << 4 | x] = block(minX + x, y, minZ + z, s).stateId();
                    }
                }
            }
            for (int z = 2; z < 14; z++) {
                for (int x = 2; x < 14; x++) {
                    final int s = surface[z << 4 | x];
                    if (s <= SEA_LEVEL + 1) continue;
                    if ((hash(minX + x, TREE_ROLL_Y, minZ + z) & 255) != 0) continue;
                    tree(blocks, x, s + 1, z);
                }
            }
            return blocks;
        }

        private int surface(int x, int z) {
            final double hills = fbm(x / 256.0, z / 256.0, 5);
            final double mountains = Math.pow(Math.max(0, fbm(x / 640.0 + 17.3, z / 640.0 - 9.1, 4)), 1.5);
            return Math.min((int) Math.floor(64 + 22 * hills + 70 * mountains), HEIGHT - 16);
        }

        private Block block(int x, int y, int z, int surface) {
            if (y > surface) return y <= SEA_LEVEL ? Block.WATER : Block.AIR;
            if (y == 0 || (y < 5 && Math.floorMod(hash(x, y, z), 5) >= y)) return Block.BEDROCK;
            if (y == surface) {
                if (surface < SEA_LEVEL) {
                    return Math.floorMod(hash(x, SEABED_ROLL_Y, z), 3) == 0 ? Block.GRAVEL : Block.SAND;
                }
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

        private static void tree(int[] blocks, int x, int baseY, int z) {
            for (int dy = 3; dy <= 6; dy++) {
                final int radius = dy <= 4 ? 2 : 1;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.abs(dx) == radius && Math.abs(dz) == radius && radius == 2) continue;
                        blocks[(baseY + dy) << 8 | (z + dz) << 4 | (x + dx)] = Block.OAK_LEAVES.stateId();
                    }
                }
            }
            for (int dy = 0; dy < 5; dy++) blocks[(baseY + dy) << 8 | z << 4 | x] = Block.OAK_LOG.stateId();
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
}
