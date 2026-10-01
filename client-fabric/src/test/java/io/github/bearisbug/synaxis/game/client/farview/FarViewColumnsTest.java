package io.github.bearisbug.synaxis.game.client.farview;

import io.github.bearisbug.synaxis.game.client.farview.FarViewColumns.Point;
import io.github.bearisbug.synaxis.game.client.farview.FarViewColumns.Position;
import io.github.bearisbug.synaxis.game.client.farview.FarViewColumns.Source;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Sample;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The point rule and the coarse expansion of {@code docs/DESIGN-M3.md} {@code API-M3-015}, the two
 * scenarios {@code 采样换算成 DH 数据点} and {@code 粗层块展开成 b 级数据源} of §19 {@code REQ-M3-025}.
 */
class FarViewColumnsTest {

    /** The pack's world: {@code min_y} -64, 256 high, as the container fixture and the 4k test map. */
    private static final int HEIGHT = 256;
    private static final int STONE = 1;
    private static final int GRASS = 9;
    private static final int WATER = 86;
    private static final int GLOWSTONE = 6000;
    private static final IntUnaryOperator EMISSION = id -> id == GLOWSTONE ? 15 : 0;

    private static Point ground(int bottom, int top, int sky, int state) {
        return new Point(bottom, top, sky, 0, state, false, 0);
    }

    private static Point air(int bottom, int top, int sky) {
        return new Point(bottom, top, sky, 0, 0, true, 0);
    }

    @Test
    void groundAloneIsAGroundPointUnderAnAirPoint() {
        // ground_y 100 is y index 100, absolute y 36 on a level whose min_y is -64
        assertEquals(List.of(air(101, 256, 15), ground(0, 101, 15, STONE)),
                FarViewColumns.points(new Sample(STONE, 0, 100, 0, 0, 0), HEIGHT, EMISSION));
    }

    @Test
    void groundUnderWaterTakesTheSkyLightThroughTheWater() {
        assertEquals(List.of(air(64, 256, 15), ground(61, 64, 15, WATER), ground(0, 61, 12, GRASS)),
                FarViewColumns.points(new Sample(GRASS, WATER, 60, 61, 63, 0), HEIGHT, EMISSION));
    }

    @Test
    void fluidAloneHasAnAirPointUnderIt() {
        assertEquals(List.of(air(21, 256, 15), ground(10, 21, 15, WATER), air(0, 10, 4)),
                FarViewColumns.points(new Sample(0, WATER, 0, 10, 20, 0), HEIGHT, EMISSION));
    }

    @Test
    void fluidDownToTheBottomHasNoAirUnderIt() {
        assertEquals(List.of(air(6, 256, 15), ground(0, 6, 15, WATER)),
                FarViewColumns.points(new Sample(0, WATER, 0, 0, 5, 0), HEIGHT, EMISSION));
    }

    @Test
    void anEmptyColumnIsOneAirPoint() {
        assertEquals(List.of(air(0, 256, 15)), FarViewColumns.points(Sample.EMPTY, HEIGHT, EMISSION));
    }

    @Test
    void aColumnUpToTheTopOfTheWorldHasNoAirAboveIt() {
        assertEquals(List.of(ground(0, 256, 15, STONE)), FarViewColumns.points(new Sample(STONE, 0, 255, 0, 0, 0), HEIGHT, EMISSION));
    }

    @Test
    void aBlockThatGivesOffLightLightsTheBlockAboveIt() {
        assertEquals(List.of(air(41, 256, 15), new Point(0, 41, 15, 14, GLOWSTONE, false, 0)),
                FarViewColumns.points(new Sample(GLOWSTONE, 0, 40, 0, 0, 0), HEIGHT, EMISSION));
    }

    @Test
    void theBiomeOfTheColumnIsOnEveryPoint() {
        for (Point point : FarViewColumns.points(new Sample(GRASS, WATER, 60, 61, 63, 2), HEIGHT, EMISSION)) {
            assertEquals(2, point.biome(), point.toString());
        }
    }

    @Test
    void everyColumnCoversTheWorldTopDownWithoutGapsAndNoPointIsAnAbsoluteY() {
        final List<Sample> columns = List.of(new Sample(STONE, 0, 100, 0, 0, 0), new Sample(GRASS, WATER, 60, 61, 63, 0),
                new Sample(0, WATER, 0, 10, 20, 0), Sample.EMPTY, new Sample(0, WATER, 0, 0, 5, 0));
        for (Sample sample : columns) {
            final List<Point> points = FarViewColumns.points(sample, HEIGHT, EMISSION);
            assertTrue(points.size() >= 1 && points.size() <= 3, points.toString());
            assertEquals(HEIGHT, points.getFirst().top(), sample + " does not reach the top of the world: " + points);
            assertEquals(0, points.getLast().bottom(), sample + " does not start at y index 0: " + points);
            for (int i = 1; i < points.size(); i++) {
                assertEquals(points.get(i - 1).bottom(), points.get(i).top(), sample + " has a gap or an overlap: " + points);
            }
            for (Point point : points) {
                assertTrue(point.bottom() >= 0 && point.top() > point.bottom(), sample + ": " + point);
            }
        }
    }

    @Test
    void aFineBlockIsOneSourceAtTheBaseLevel() {
        final List<Sample> samples = distinct();
        final Source at = FarViewColumns.fine(0, 0, 2, 3, 5, samples);
        assertEquals(new Position(8, 3, 5), at.position());
        assertSame(samples.get(17 * 64 + 9), at.columns().get(17 * 64 + 9));
        assertEquals(new Position(8, -16 + 15, 0), FarViewColumns.fine(-1, 0, 2, 15, 0, samples).position());
        assertEquals(new Position(7, -32 + 31, 64 + 1), FarViewColumns.fine(-1, 2, 1, 31, 1, samples).position());
    }

    @Test
    void aCoarseBlockExpandsIntoSixteenSourcesAtBaseLevelTwo() {
        final List<Sample> coarse = distinct();
        final List<Source> sources = FarViewColumns.coarse(-1, 0, 2, 1, 2, coarse);
        assertEquals(4, FarViewColumns.expansion(2));
        assertEquals(16, sources.size());
        for (int ez = 0; ez < 4; ez++) {
            for (int ex = 0; ex < 4; ex++) {
                final Source source = sources.get(ez * 4 + ex);
                assertEquals(new Position(8, -12 + ex, 8 + ez), source.position(), "source " + ex + "," + ez);
                for (int z = 0; z < 64; z++) {
                    for (int x = 0; x < 64; x++) {
                        final Sample expected = coarse.get(((ez * 64 + z) >> 2) * 64 + ((ex * 64 + x) >> 2));
                        assertSame(expected, source.columns().get(z * 64 + x), "source " + ex + "," + ez + " column " + x + "," + z);
                    }
                }
            }
        }
        // every 4 x 4 columns are one coarse sample, so neighbouring groups differ
        assertSame(sources.getFirst().columns().get(0), sources.getFirst().columns().get(3 * 64 + 3));
        assertTrue(sources.getFirst().columns().get(0) != sources.getFirst().columns().get(4));
    }

    @Test
    void aCoarseBlockExpandsIntoSixtyFourSourcesAtBaseLevelOne() {
        final List<Source> sources = FarViewColumns.coarse(2, -1, 1, 3, 0, distinct());
        assertEquals(8, FarViewColumns.expansion(1));
        assertEquals(64, sources.size());
        // sx = tile_x x (4096 >> 7) + bx x 8 + ex
        assertEquals(new Position(7, 64 + 24, -32), sources.getFirst().position());
        assertEquals(new Position(7, 64 + 24 + 7, -32 + 7), sources.getLast().position());
    }

    @Test
    void aCoarseBlockUnexpandedIsOneSourceAtTheCoarseLevel() {
        final List<Sample> coarse = distinct();
        final Source source = FarViewColumns.coarseUnexpanded(-1, 0, 3, 1, coarse);
        assertEquals(new Position(10, -4 + 3, 1), source.position());
        assertSame(coarse.get(100), source.columns().get(100));
    }

    /** 4,096 samples no two of which are equal. */
    private static List<Sample> distinct() {
        final List<Sample> samples = new ArrayList<>(4096);
        for (int i = 0; i < 4096; i++) samples.add(new Sample(1 + i / 256, 0, i % 256, 0, 0, 0));
        return samples;
    }
}
