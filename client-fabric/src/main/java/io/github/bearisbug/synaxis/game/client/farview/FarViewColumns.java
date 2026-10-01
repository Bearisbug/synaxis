package io.github.bearisbug.synaxis.game.client.farview;

import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Sample;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * From height field samples to what Distant Horizons stores ({@code docs/DESIGN-M3.md}
 * {@code API-M3-015} 写库条 and 点规则), as pure functions: the 1 to 3 data points of a column, and
 * which data source a block of samples becomes, the coarse blocks of a neighbouring tile expanded
 * to the base level. The DH side ({@code farview.dh}) only wraps what these return.
 * <p>
 * A data source is 64 × 64 columns at a section detail level {@code 6 + b} (DH's section detail
 * level 6 is one block a column), so its position is that level and a coordinate on each axis in
 * units of {@code 64 << b} blocks.
 */
public final class FarViewColumns {

    /** DH's section detail level of one block per column ({@code DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL}). */
    public static final int SECTION_BLOCK_DETAIL = 6;
    /** Columns on a side of a data source ({@code FullDataSourceV2.WIDTH}), also the samples on a side of a pack block. */
    public static final int WIDTH = FarViewPack.BLOCK_SIDE;

    /** The sky light of a point open to the sky. */
    private static final int FULL_SKY = 15;

    private FarViewColumns() {
    }

    /**
     * One data point: the y range is a half open interval of y indices (absolute y minus the level's
     * {@code getMinHeight()}, which is what the pack stores), the light is that of the block above
     * its top face.
     *
     * @param bottom     the lowest y index it covers
     * @param top        one above the highest y index it covers
     * @param skyLight   0 to 15
     * @param blockLight 0 to 15
     * @param state      the global palette id of its block state, 0 for an air point
     * @param air        whether it is an air point, which DH takes its own air wrapper for
     * @param biome      the index into the pack's biome table
     */
    public record Point(int bottom, int top, int skyLight, int blockLight, int state, boolean air, int biome) {
    }

    /**
     * Where a data source goes.
     *
     * @param sectionDetail DH's section detail level, {@code 6 + b} for the level b of its columns
     * @param x             the position on x, in units of {@code 64 << b} blocks
     * @param z             the position on z, likewise
     */
    public record Position(int sectionDetail, int x, int z) {
    }

    /**
     * One data source to hand to DH.
     *
     * @param position where it goes
     * @param columns  its 4,096 columns as samples, {@code z × 64 + x}
     */
    public record Source(Position position, List<Sample> columns) {
    }

    /**
     * The data points of one column by the point rule, top first.
     *
     * @param sample   the column
     * @param height   the world height of the pack, which the topmost air point reaches
     * @param emission the light a block state gives off, by its global palette id
     * @return 1 to 3 points, each just below the one before it
     */
    public static List<Point> points(Sample sample, int height, IntUnaryOperator emission) {
        final int biome = sample.biome();
        final int top = sample.top();
        final List<Point> points = new ArrayList<>(3);
        if (top + 1 < height) points.add(new Point(top + 1, height, FULL_SKY, 0, 0, true, biome));
        if (sample.hasFluid()) {
            final int depth = sample.fluidTop() - sample.fluidBottom() + 1;
            points.add(new Point(sample.fluidBottom(), sample.fluidTop() + 1, FULL_SKY, blockLight(sample.fluidState(), emission),
                    sample.fluidState(), false, biome));
            final int under = Math.max(0, FULL_SKY - depth);
            if (sample.hasGround()) {
                points.add(new Point(0, sample.groundY() + 1, under, blockLight(sample.groundState(), emission),
                        sample.groundState(), false, biome));
            } else if (sample.fluidBottom() > 0) {
                points.add(new Point(0, sample.fluidBottom(), under, 0, 0, true, biome));
            }
        } else if (sample.hasGround()) {
            points.add(new Point(0, sample.groundY() + 1, FULL_SKY, blockLight(sample.groundState(), emission),
                    sample.groundState(), false, biome));
        }
        return points;
    }

    /** The block light a state gives the block above it: one less than it gives off. */
    private static int blockLight(int state, IntUnaryOperator emission) {
        return Math.max(0, emission.applyAsInt(state) - 1);
    }

    /**
     * How many data sources a coarse block becomes on each side: {@code 2^(c - b)}.
     *
     * @param baseLevel b, 1 or 2
     * @return 4 for b = 2, 8 for b = 1
     */
    public static int expansion(int baseLevel) {
        if (baseLevel != 1 && baseLevel != 2) throw new IllegalArgumentException("base_level " + baseLevel + " is not 1 or 2");
        return 1 << (FarViewPack.COARSE_LEVEL - baseLevel);
    }

    /**
     * The data source of one fine block of a whole pack.
     *
     * @param tileX     the tile
     * @param tileZ     the tile
     * @param baseLevel b
     * @param bx        the block in the tile
     * @param bz        the block in the tile
     * @param samples   its 4,096 samples
     * @return the source at section detail level {@code 6 + b}
     */
    public static Source fine(int tileX, int tileZ, int baseLevel, int bx, int bz, List<Sample> samples) {
        final int perTile = FarViewPack.blocksPerSide(baseLevel);
        return new Source(new Position(SECTION_BLOCK_DETAIL + baseLevel, tileX * perTile + bx, tileZ * perTile + bz),
                List.copyOf(samples));
    }

    /**
     * The data sources one coarse block of a neighbouring tile expands into: {@code k × k} sources
     * at the base level ({@code k = 2^(c - b)}), each column the coarse sample covering it, so every
     * {@code k × k} columns are the same sample.
     *
     * @param tileX     the tile
     * @param tileZ     the tile
     * @param baseLevel b, the level the fine layers are written at
     * @param bx        the coarse block in the tile, 0 to 3
     * @param bz        the coarse block in the tile, 0 to 3
     * @param samples   its 4,096 coarse samples
     * @return the sources by {@code ez} and then {@code ex}
     */
    public static List<Source> coarse(int tileX, int tileZ, int baseLevel, int bx, int bz, List<Sample> samples) {
        final int k = expansion(baseLevel);
        final int shift = FarViewPack.COARSE_LEVEL - baseLevel;
        final int perTile = FarViewPack.blocksPerSide(baseLevel);
        final List<Source> sources = new ArrayList<>(k * k);
        for (int ez = 0; ez < k; ez++) {
            for (int ex = 0; ex < k; ex++) {
                final Sample[] columns = new Sample[FarViewPack.BLOCK_SAMPLES];
                for (int z = 0; z < WIDTH; z++) {
                    final int row = ((ez * WIDTH + z) >> shift) * WIDTH;
                    for (int x = 0; x < WIDTH; x++) columns[z * WIDTH + x] = samples.get(row + ((ex * WIDTH + x) >> shift));
                }
                sources.add(new Source(new Position(SECTION_BLOCK_DETAIL + baseLevel, tileX * perTile + bx * k + ex,
                        tileZ * perTile + bz * k + ez), List.of(columns)));
            }
        }
        return sources;
    }

    /**
     * One coarse block as a single data source at the coarse level, without the expansion: what
     * the {@code v1-coarse-only} counter-proof writes, a level DH does not draw near the player.
     *
     * @param tileX   the tile
     * @param tileZ   the tile
     * @param bx      the coarse block in the tile
     * @param bz      the coarse block in the tile
     * @param samples its 4,096 coarse samples
     * @return the source at section detail level {@code 6 + c}
     */
    public static Source coarseUnexpanded(int tileX, int tileZ, int bx, int bz, List<Sample> samples) {
        final int perTile = FarViewPack.blocksPerSide(FarViewPack.COARSE_LEVEL);
        return new Source(new Position(SECTION_BLOCK_DETAIL + FarViewPack.COARSE_LEVEL, tileX * perTile + bx, tileZ * perTile + bz),
                List.copyOf(samples));
    }
}
