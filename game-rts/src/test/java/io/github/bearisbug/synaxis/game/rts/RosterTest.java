package io.github.bearisbug.synaxis.game.rts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The size of the roster as the operator names it ({@code synaxis.app.units}): the default is the
 * acceptance roster, and a value that is not a number or leaves no unit for the player is refused
 * by name rather than quietly replaced.
 */
class RosterTest {

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
}
