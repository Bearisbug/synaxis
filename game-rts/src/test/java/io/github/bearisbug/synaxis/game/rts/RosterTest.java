package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.coordinate.Pos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The size of the roster as the operator names it ({@code synaxis.app.units}): the default is the
 * acceptance roster, and a value that is not a number or leaves no unit for the player is refused
 * by name rather than quietly replaced. Where the player's units stand: rings around the spawn for
 * the default roster, an even spread over the whole map for a bigger one, so that the load test of
 * {@code docs/DESIGN-M2.md} §15 ({@code docs/TEST.md} TC-GAME-068) measures the unit snapshot with
 * as many units in a player's view as the map holds per view, not every unit of the match.
 */
class RosterTest {

    /** The map TC-GAME-068 runs on, and its navigation mesh, which needs no running match. */
    private static final MapPreset MAP = MapPreset.DEFAULT;
    private static final NavGrid GRID = NavGrid.of(new MapTerrain(MAP));

    @Test
    void theDefaultIsTheAcceptanceRoster() {
        assertEquals(Roster.DEFAULT_UNITS, Roster.units(null));
        assertEquals(67, Roster.DEFAULT_UNITS, "docs/TEST.md §3 counts on 64 own units and the three fixed ones");
    }

    @Test
    void aNumberIsTakenAsGiven() {
        assertEquals(1000, Roster.units("1000"));
        assertEquals(1000, Roster.units(" 1000 "));
        assertEquals(Roster.FIXED_UNITS + 1, Roster.units(String.valueOf(Roster.FIXED_UNITS + 1)),
                "the smallest roster is the three fixed units and one of the player's own");
    }

    @Test
    void aValueThatIsNotANumberIsRefusedByName() {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Roster.units("many"));
        assertTrue(refused.getMessage().contains(Roster.UNITS_PROPERTY + "=many"),
                "the operator has to see which value was refused: " + refused.getMessage());
    }

    @Test
    void aRosterWithNothingForThePlayerIsRefused() {
        for (final String tooFew : new String[] {"3", "0", "-5"}) {
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> Roster.units(tooFew), tooFew);
            assertTrue(refused.getMessage().contains(Roster.UNITS_PROPERTY + "=" + tooFew), refused.getMessage());
        }
    }

    @Test
    void theNumberIsReadOffTheSystemProperty() {
        final String previous = System.getProperty(Roster.UNITS_PROPERTY);
        try {
            System.clearProperty(Roster.UNITS_PROPERTY);
            assertEquals(Roster.DEFAULT_UNITS, Roster.units());
            System.setProperty(Roster.UNITS_PROPERTY, "120");
            assertEquals(120, Roster.units());
        } finally {
            if (previous == null) System.clearProperty(Roster.UNITS_PROPERTY);
            else System.setProperty(Roster.UNITS_PROPERTY, previous);
        }
    }

    @Test
    void theDefaultRosterStaysInRingsAroundTheSpawn() {
        final Pos spawn = new Pos(256.5, MapTerrain.GROUND_HEIGHT, 256.5);
        assertTrue(GRID.isWalkable(256, 256), "the test puts the spawn on the middle column of " + MAP.id());

        final List<Pos> stands = Roster.ownStands(GRID, MAP, spawn, Roster.DEFAULT_UNITS);
        assertEquals(Roster.DEFAULT_UNITS - Roster.FIXED_UNITS, stands.size());
        assertEquals(new Pos(255.5, MapTerrain.GROUND_HEIGHT, 255.5), stands.getFirst(),
                "unit 1 stands on the first column of the first ring, as docs/TEST.md §3 has it");
        for (final Pos stand : stands) {
            final double ring = Math.max(Math.abs(stand.x() - spawn.x()), Math.abs(stand.z() - spawn.z()));
            assertTrue(ring >= 1 && ring <= 5, "64 units fill the rings 1 to 4, a pillar or two push into 5: " + stand);
        }
    }

    @Test
    void aThousandUnitsAreSpreadOverTheWholeMap() {
        final List<Pos> stands = Roster.ownStands(GRID, MAP, new Pos(256.5, MapTerrain.GROUND_HEIGHT, 256.5), 1000);
        assertEquals(997, stands.size(), "the three fixed units are not in this list");

        // ⌊√(512 × 512 ÷ 997)⌋ = 16: 32 × 32 grid points, centred, so the first is (8, 8)
        final int spacing = 16;
        assertEquals(spacing, Roster.spacing(MAP, 997));
        final int columns = 32;
        final int origin = 8;
        final Set<Long> columnsTaken = new HashSet<>();
        for (int unit = 0; unit < stands.size(); unit++) {
            final Pos stand = stands.get(unit);
            final int x = (int) Math.floor(stand.x());
            final int z = (int) Math.floor(stand.z());
            assertTrue(GRID.isWalkable(x, z), "unit " + (unit + 1) + " stands on a column nothing walks on: " + stand);
            assertTrue(columnsTaken.add(((long) x << 32) | z), "unit " + (unit + 1) + " shares its column: " + stand);
            final int gridX = origin + (unit % columns) * spacing;
            final int gridZ = origin + (unit / columns) * spacing;
            assertTrue(Math.abs(x - gridX) <= spacing / 2 && Math.abs(z - gridZ) <= spacing / 2,
                    "unit " + (unit + 1) + " belongs to grid point (" + gridX + ", " + gridZ + ") but stands at "
                            + stand);
        }

        final int entityViewDistance = 8;
        final int inView = mostInAnyView(stands, entityViewDistance);
        assertTrue(inView <= 17 * 17,
                "one unit per 16 × 16 cell puts at most 17 × 17 of them in a view of 17 × 17 chunks: " + inView);
        assertTrue(inView >= 250,
                "the middle of the map is as dense as anywhere else, §15 reckons about 282 in view: " + inView);
    }

    @Test
    void theProfileTierPutsNoMoreThanFortyNineUnitsInAnyView() {
        // docs/DESIGN-M2.md §15, the unit snapshot row: s = ⌈16(2e + 1) ÷ 7⌉ leaves at most 7 grid
        // columns per side of a view, and ⌊262,144 ÷ s²⌋ own units fill rts-512 at that spacing. e is
        // the entity view distance the RTS process runs at, 8 (server-app Main)
        final int entityViewDistance = 8;
        final int spacing = Math.ceilDiv(16 * (2 * entityViewDistance + 1), 7);
        final int own = MAP.sizeX() * MAP.sizeZ() / (spacing * spacing);
        assertEquals(39, spacing);
        assertEquals(175, own + Roster.FIXED_UNITS, "the -Dsynaxis.app.units of the profile tier");
        assertEquals(spacing, Roster.spacing(MAP, own), "the roster spreads them at the spacing of the tier");

        final List<Pos> stands = Roster.ownStands(GRID, MAP,
                new Pos(256.5, MapTerrain.GROUND_HEIGHT, 256.5), own + Roster.FIXED_UNITS);
        assertEquals(own, stands.size());
        final int inView = mostInAnyView(stands, entityViewDistance);
        assertTrue(inView <= 49, "a player sees " + inView + " of the player's units");
    }

    /**
     * The most units of a list any player standing on the map has in view: a player sees the
     * entities of the chunks at most {@code entityViewDistance} chunks away along each axis.
     */
    private static int mostInAnyView(List<Pos> stands, int entityViewDistance) {
        final int chunks = MAP.sizeX() / 16;
        int most = 0;
        for (int playerX = 0; playerX < chunks; playerX++) {
            for (int playerZ = 0; playerZ < chunks; playerZ++) {
                int inView = 0;
                for (final Pos stand : stands) {
                    final int chunkX = (int) Math.floor(stand.x()) >> 4;
                    final int chunkZ = (int) Math.floor(stand.z()) >> 4;
                    if (Math.abs(chunkX - playerX) <= entityViewDistance
                            && Math.abs(chunkZ - playerZ) <= entityViewDistance) inView++;
                }
                most = Math.max(most, inView);
            }
        }
        return most;
    }
}
