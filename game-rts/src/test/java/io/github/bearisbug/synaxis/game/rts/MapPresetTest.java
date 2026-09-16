package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.coordinate.Pos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map presets of REQ-M2-010 and the §9 constraints on their generation parameters.
 */
class MapPresetTest {

    @Test
    void anUnknownIdHasNoPreset() {
        assertNull(MapPreset.byId("no-such-map"));
        assertNotNull(MapPreset.byId(MapPreset.DEFAULT.id()));
        // The release checklist of §23 rolls a bad map back by naming another usable preset
        assertTrue(MapPreset.knownIds().size() >= 2, MapPreset.knownIds().toString());
    }

    @Test
    void theShippedPresetsAreValid() {
        assertNull(MapPreset.DEFAULT.invalidParameters());
        assertNull(MapPreset.SMALL.invalidParameters());
        assertEquals(1024, MapPreset.DEFAULT.chunkCount());
    }

    @Test
    void aSizeThatIsNotAWholeNumberOfChunksIsRefused() {
        final String problem = new MapPreset("broken", 500, 512, 64, 0).invalidParameters();
        assertNotNull(problem);
        assertTrue(problem.contains("size_x=500"), problem);
        assertTrue(problem.contains("multiple of 16"), problem);
    }

    @Test
    void aSizeBeyondTheMapLimitIsRefused() {
        final String problem = new MapPreset("broken", 1024, 512, 64, 0).invalidParameters();
        assertNotNull(problem);
        assertTrue(problem.contains("size_x=1024"), problem);
    }

    @Test
    void aWorldHeightOutsideTheAllowedRangeIsRefused() {
        final String problem = new MapPreset("broken", 512, 512, 0, 0).invalidParameters();
        assertNotNull(problem);
        assertTrue(problem.contains("height=0"), problem);
        assertTrue(problem.contains("multiple of 16"), problem);
    }

    @Test
    void theBoundaryConstraintPullsAPositionBackOntoTheMap() {
        final MapPreset preset = MapPreset.SMALL;
        assertEquals(new Pos(127, 40, 0), preset.clamp(new Pos(99_999, 40, -5)));
        assertEquals(new Pos(0, 40, 127), preset.clamp(new Pos(-1, 40, 500)));
        // A position already inside is left alone, yaw and pitch included
        final Pos inside = new Pos(12.5, 41, 13.5, 90f, 10f);
        assertEquals(inside, preset.clamp(inside));
    }
}
