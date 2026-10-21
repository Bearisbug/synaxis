package io.github.bearisbug.synaxis.game.protocol;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Looking up the picking box of an archetype number that this build may not know
 * ({@code docs/DESIGN-M2.md} §9: a newer server may field archetypes an older client does not).
 */
class ArchetypeBoxTest {

    @Test
    void findHasTheBoxOfAKnownArchetypeAndNothingForAnUnknownOne() {
        assertEquals(Optional.of(ArchetypeBox.of(0)), ArchetypeBox.find(0));
        assertEquals(Optional.empty(), ArchetypeBox.find(1));
        assertEquals(Optional.empty(), ArchetypeBox.find(-1));
    }
}
