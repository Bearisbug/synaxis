package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.coordinate.Vec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capture rules of a beacon with coordinates and step lengths only ({@code docs/DESIGN-PLAY.md}
 * §6.7.2): one player alone fills it at 10% a second, two stop it, and someone else's beacon drains at
 * 20% a second before it fills for the newcomer.
 */
class BeaconTest {

    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final long SECOND = Duration.ofSeconds(1).toNanos();

    private final Beacon beacon = new Beacon('A', -12, 40, -12);

    @Test
    void theCircleIsThreeBlocksAcrossFromTheCentreAndTwoUpOrDown() {
        // The centre of the block at -12, -12 is -11.5, -11.5
        assertTrue(beacon.contains(new Vec(-11.5, 40, -11.5)));
        assertTrue(beacon.contains(new Vec(-11.5 + 3, 40, -11.5)));
        assertFalse(beacon.contains(new Vec(-11.5 + 3.01, 40, -11.5)));
        assertTrue(beacon.contains(new Vec(-11.5 + 2.1, 40, -11.5 - 2.1)));
        assertFalse(beacon.contains(new Vec(-11.5 + 2.2, 40, -11.5 - 2.2)));
        assertTrue(beacon.contains(new Vec(-11.5, 42, -11.5)));
        assertTrue(beacon.contains(new Vec(-11.5, 38, -11.5)));
        assertFalse(beacon.contains(new Vec(-11.5, 42.01, -11.5)));
        assertFalse(beacon.contains(new Vec(-11.5, 37.99, -11.5)));
    }

    @Test
    void onePlayerAloneFillsItInTenSecondsAndOwnsItOnce() {
        for (int tick = 0; tick < 199; tick++) assertNull(beacon.advance(Set.of(ALICE), SECOND / 20));
        assertEquals(99, beacon.percent());
        assertNull(beacon.owner());

        assertEquals(ALICE, beacon.advance(Set.of(ALICE), SECOND / 20), "filling it up at 10 s is a capture");
        assertEquals(ALICE, beacon.owner());
        assertEquals(100, beacon.percent());
        assertNull(beacon.advance(Set.of(ALICE), SECOND), "standing on one's own beacon captured it again");
    }

    @Test
    void halfwayIsFivePercentPerHalfSecond() {
        beacon.advance(Set.of(ALICE), 5 * SECOND);

        assertEquals(50, beacon.percent());
        assertNull(beacon.owner());
    }

    @Test
    void twoInTheCircleFreezeItAndNobodyLeavesItAsItIs() {
        beacon.advance(Set.of(ALICE), 4 * SECOND);

        assertNull(beacon.advance(Set.of(ALICE, BOB), 3 * SECOND));
        assertEquals(40, beacon.percent(), "the progress moved while two were in the circle");
        assertNull(beacon.advance(Set.of(), 3 * SECOND));
        assertEquals(40, beacon.percent(), "the progress moved with nobody in the circle");
    }

    @Test
    void someoneElsesBeaconDrainsAtTwentyPercentFirstAndStaysTheirsUntilEmpty() {
        beacon.advance(Set.of(ALICE), 10 * SECOND);

        assertNull(beacon.advance(Set.of(BOB), 2500 * SECOND / 1000));
        assertEquals(50, beacon.percent(), "2.5 s at 20% a second takes 50% off");
        assertEquals(ALICE, beacon.owner(), "the owner lost the beacon before it was drained");
        assertNull(beacon.advance(Set.of(BOB), 2500 * SECOND / 1000));
        assertEquals(0, beacon.percent());
        assertNull(beacon.owner(), "a drained beacon still had an owner");

        assertNull(beacon.advance(Set.of(BOB), 9 * SECOND));
        assertEquals(BOB, beacon.advance(Set.of(BOB), SECOND), "after the drain it fills for the newcomer in 10 s");
    }

    @Test
    void aStepThatDrainsAndFillsCarriesTheRestOver() {
        beacon.advance(Set.of(ALICE), 2 * SECOND);

        // 20% drains in 1 s, and the other 3 s fill 30% for Bob
        beacon.advance(Set.of(BOB), 4 * SECOND);

        assertEquals(30, beacon.percent());
        beacon.advance(Set.of(BOB), 7 * SECOND);
        assertEquals(BOB, beacon.owner());
    }

    @Test
    void aParticipantWhoLeftLosesWhatTheyHeld() {
        beacon.advance(Set.of(ALICE), 10 * SECOND);
        beacon.forget(BOB);
        assertEquals(ALICE, beacon.owner(), "forgetting someone else changed the beacon");

        beacon.forget(ALICE);

        assertNull(beacon.owner());
        assertEquals(0, beacon.percent());
    }
}
