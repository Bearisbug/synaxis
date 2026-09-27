package io.github.bearisbug.synaxis.loadtest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The movement profiles ({@code docs/DESIGN-PROD.md} {@code API-PROD-025}; the {@code gate} profile
 * of {@code docs/DESIGN-M3.md} §26 M3-E track D): the town crowd stays in sight of its spawn, and a
 * gate bot judges a frame the way the shard's {@code ShardGates} does, waits, walks in at 10 blocks
 * a second and backs out of a frame that sent it nowhere.
 */
public class ProfileTest {

    private static final long TICK = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    /** The two gates of the example shard directory of {@code API-M3-010}. */
    private static final Profile.Frame R0_EAST = new Profile.Frame(2032, 0, 1016, 2047, 255, 1031);
    private static final Profile.Frame R1_WEST = new Profile.Frame(2048, 0, 1016, 2063, 255, 1031);

    @Test
    public void aTownBotStaysWithin64BlocksOfItsSpawnFor600Seconds() {
        double farthest = 0;
        for (int index = 0; index < 500; index++) {
            for (double seconds = 0; seconds <= 600; seconds += 0.05) {
                final double[] at = Profile.TOWN.position(index, 100.5, 40, -20.5, seconds);
                farthest = Math.max(farthest, Math.hypot(at[0] - 100.5, at[2] + 20.5));
                assertEquals(40, at[1], "a town bot keeps to the ground");
            }
        }
        assertTrue(farthest <= 64, "the farthest a town bot got from its spawn: " + farthest);
    }

    @Test
    public void aFrameHoldsThePositionsWhoseBlockIsInIt() {
        assertFalse(R0_EAST.contains(2031.99, 100, 1020), "x = 2031.99 is in block 2031, outside the frame");
        assertTrue(R0_EAST.contains(2032.0, 100, 1020), "x = 2032.0 is in block 2032, the first of the frame");
        assertTrue(R0_EAST.contains(2047.99, 100, 1020), "the last block of the frame counts whole");
        assertFalse(R0_EAST.contains(2048.0, 100, 1020));
        assertTrue(R0_EAST.contains(2040, 0.0, 1016.0));
        assertFalse(R0_EAST.contains(2040, -0.01, 1020), "below the lowest block");
        assertTrue(R0_EAST.contains(2040, 255.99, 1031.99));
        assertFalse(R0_EAST.contains(2040, 256.0, 1020));
        assertFalse(R0_EAST.contains(2040, 100, 1015.99));
        // Rounded down, not towards zero: -0.5 is in block -1
        final Profile.Frame negative = new Profile.Frame(-16, 0, -16, -1, 10, -1);
        assertTrue(negative.contains(-0.5, 5, -0.5));
        assertTrue(negative.contains(-16.0, 5, -16.0));
        assertFalse(negative.contains(-16.01, 5, -8));
        assertFalse(negative.contains(0.0, 5, -8));
    }

    @Test
    public void gatesAreReadFromTheCommandLine() {
        final Args args = new Args(List.of("--gates", "2032,0,1016,2047,255,1031; 2048,0,1016,2063,255,1031"));
        assertEquals(List.of(R0_EAST, R1_WEST), args.gates("gates"));
        assertEquals(List.of(), new Args(List.of()).gates("gates"));
        assertThrows(IllegalArgumentException.class, () -> gates("2032,0,1016,2047,255"), "five numbers");
        assertThrows(IllegalArgumentException.class, () -> gates("2032,0,1016,2047,255,x"), "not a number");
        assertThrows(IllegalArgumentException.class, () -> gates("2047,0,1016,2032,255,1031"), "min above max");
    }

    /**
     * The trip of {@code docs/DESIGN-M3.md} §15, the transfer row: arrived in r0 at the arrival point of
     * r0-east, the bot stands 6 s, walks the 7.5 blocks to the frame in 0.75 s and stays at its centre.
     */
    @Test
    public void aGateBotWaitsThenWalksIntoTheNearestFrameAtTenBlocksASecond() {
        final Profile.GateWalk walk = new Profile.GateWalk(List.of(R1_WEST, R0_EAST), 2024.5, 200, 1024.5, 0,
                6, 30 * SECOND);
        long entered = -1;
        for (long now = 0; now <= 20 * SECOND; now += TICK) {
            final Profile.GateWalk.Step step = walk.next(now);
            assertFalse(step.noTransfer());
            if (now <= 6 * SECOND) {
                assertEquals(2024.5, step.x(), "it stands the dwell out at the spawn, t = " + now);
                assertEquals(200, step.y());
                assertEquals(1024.5, step.z());
            }
            if (now == 6 * SECOND + 10 * TICK) {
                final double walked = Math.sqrt(Math.pow(step.x() - 2024.5, 2) + Math.pow(step.z() - 1024.5, 2));
                assertEquals(5.0, walked, 1e-9, "10 blocks a second, half a second in");
                assertEquals(200, step.y(), "a frame as tall as the world is walked into level");
            }
            if (step.entered()) {
                assertEquals(-1, entered, "a try starts once");
                entered = now;
                assertTrue(R0_EAST.contains(step.x(), step.y(), step.z()), "the first position in the frame starts it");
            } else if (entered < 0) {
                assertFalse(R0_EAST.contains(step.x(), step.y(), step.z()), "no position in the frame before the try");
            }
        }
        // x reaches 2032 after 7.504 blocks on a line a little off x: 0.7504 s, the 16th tick
        assertEquals(6 * SECOND + 16 * TICK, entered);
        final Profile.GateWalk.Step still = walk.next(20 * SECOND);
        assertEquals(2040, still.x(), 1e-9, "it stays at the centre of the frame");
        assertEquals(1024, still.z(), 1e-9);

        final Profile.GateWalk back = new Profile.GateWalk(List.of(R0_EAST, R1_WEST), 2071.5, 200, 1024.5, 0,
                6, 30 * SECOND);
        final Profile.GateWalk.Step there = back.next(20 * SECOND);
        assertEquals(2056, there.x(), 1e-9, "from the arrival point in r1 the nearest frame is r1-west");
        assertTrue(R1_WEST.contains(there.x(), there.y(), there.z()));
    }

    @Test
    public void aGateBotThatGetsNoTransferBacksOutThreeBlocksAndWalksInAgain() {
        final Profile.GateWalk walk = new Profile.GateWalk(List.of(R0_EAST, R1_WEST), 2024.5, 200, 1024.5, 0,
                6, 30 * SECOND);
        final long firstTry = 6 * SECOND + 16 * TICK;
        int tries = 0;
        int noTransfers = 0;
        long noTransferAt = -1;
        long secondTry = -1;
        double farthestOut = 0;
        for (long now = 0; now <= 50 * SECOND; now += TICK) {
            final Profile.GateWalk.Step step = walk.next(now);
            if (step.entered()) {
                tries++;
                if (tries == 2) secondTry = now;
            }
            if (step.noTransfer()) {
                noTransfers++;
                noTransferAt = now;
            }
            if (noTransferAt >= 0 && secondTry < 0)
                farthestOut = Math.max(farthestOut, R0_EAST.distance(step.x(), step.y(), step.z()));
        }
        assertEquals(firstTry + 30 * SECOND, noTransferAt, "no Transfer 30 s after the first position in the frame");
        assertEquals(1, noTransfers);
        assertEquals(2, tries, "walked out and in again: a second try");
        // Back along the line it came in on until 3 blocks past the edge; the line is not quite along x
        assertTrue(farthestOut > 2.99 && farthestOut <= 3.0, "how far out of the frame it went: " + farthestOut);
        // 8 blocks back to the edge and 3 more, reached on the tick at 1.15 s; 3 blocks in again from there
        assertTrue(secondTry - noTransferAt >= 1_400_000_000L && secondTry - noTransferAt <= 1_500_000_000L,
                "the second try " + (secondTry - noTransferAt) / 1e6 + " ms after the first ended");
    }

    @Test
    public void aFrameAboveOrBelowTheBotIsWalkedToItsMiddleHeight() {
        final Profile.Frame low = new Profile.Frame(10, 60, -5, 19, 69, 4);
        final Profile.GateWalk walk = new Profile.GateWalk(List.of(low), 0.5, 200, 0.5, 0, 0, 30 * SECOND);
        final Profile.GateWalk.Step end = walk.next(60 * SECOND);
        assertEquals(15, end.x(), 1e-9);
        assertEquals(65, end.y(), 1e-9);
        assertEquals(0, end.z(), 1e-9);
    }

    private static List<Profile.Frame> gates(String value) {
        return new Args(List.of("--gates", value)).gates("gates");
    }
}
