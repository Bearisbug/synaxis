package io.github.bearisbug.synaxis.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DimensionTypeBuilderTest {
    @Test
    void testCoordinateScale() {
        assertThrows(IllegalArgumentException.class, () -> DimensionType.builder().coordinateScale(0));
        assertThrows(IllegalArgumentException.class, () -> DimensionType.builder().coordinateScale(30000001));

        assertDoesNotThrow(() -> DimensionType.builder().coordinateScale(1.5));
    }

    @Test
    void testMinY() {
        assertThrows(IllegalArgumentException.class, () -> DimensionType.builder().minY(-2048));
        assertThrows(IllegalArgumentException.class, () -> DimensionType.builder().minY(2032));
        assertThrows(IllegalArgumentException.class, () -> DimensionType.builder().minY(7));

        assertDoesNotThrow(() -> DimensionType.builder().minY(-16));
    }

    @Test
    void testHeight() {
        assertThrows(IllegalArgumentException.class, () -> DimensionType.builder().height(0));
        assertThrows(IllegalArgumentException.class, () -> DimensionType.builder().height(4080));
        assertThrows(IllegalArgumentException.class, () -> DimensionType.builder().height(17));

        assertDoesNotThrow(() -> DimensionType.builder().height(16));
    }

    @Test
    void testLogicalHeight() {
        assertThrows(IllegalArgumentException.class, () -> DimensionType.builder().logicalHeight(-1));

        assertDoesNotThrow(() -> DimensionType.builder().logicalHeight(17));
    }

    @Test
    void testMonsterSpawnBlockLightLimit() {
        assertThrows(IllegalArgumentException.class, () -> DimensionType.builder().monsterSpawnBlockLightLimit(-1));
        assertThrows(IllegalArgumentException.class, () -> DimensionType.builder().monsterSpawnBlockLightLimit(16));

        assertDoesNotThrow(() -> DimensionType.builder().monsterSpawnBlockLightLimit(15));
    }

    // maxY is the first y above the world, VANILLA_MAX_Y the highest block
    @Test
    void maxYIsExclusive() {
        assertEquals(DimensionType.VANILLA_MAX_Y + 1, DimensionType.builder().build().maxY());
    }

    @Test
    void testBuild() {
        final String logicalHeight = assertThrows(IllegalArgumentException.class, () -> DimensionType.builder()
                .height(32)
                .logicalHeight(33)
                .build()).getMessage();
        assertTrue(logicalHeight.contains("33") && logicalHeight.contains("32"), logicalHeight);

        final String buildHeight = assertThrows(IllegalArgumentException.class, () -> DimensionType.builder()
                .height(32)
                .logicalHeight(32)
                .minY(2016)
                .build()).getMessage();
        assertTrue(buildHeight.contains("2047"), buildHeight);

        assertDoesNotThrow(() -> DimensionType.builder()
                .minY(2000)
                .height(32)
                .logicalHeight(10)
                .build());
    }
}