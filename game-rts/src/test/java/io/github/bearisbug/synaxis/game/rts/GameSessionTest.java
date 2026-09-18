package io.github.bearisbug.synaxis.game.rts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per connection budgets of {@code docs/DESIGN-M2.md} §15 and the flood gate of §14, which are
 * two different things and have to keep being two different things: going over a budget is answered
 * in band, only a flood disconnects.
 */
class GameSessionTest {

    /** More than the flood threshold, the rate {@code TC-GAME-025} floods at. */
    private static final int FLOODING = 400;

    /** The normal peak of §2, which must never cost anybody their connection. */
    private static final int PEAK = 95;

    @Test
    void oneOperationSpendsOneTokenNoMatterHowManyPacketsItTakes() {
        final GameSession session = new GameSession();

        // One click on eight selected units: eight packets, one order_group, one token
        for (int packet = 0; packet < 8; packet++) {
            assertTrue(session.allowOrder(0, 7), "packet " + packet + " of a single operation was refused");
        }

        // Nine more operations fit in the same second, the eleventh one does not
        for (int group = 8; group < 17; group++) {
            assertTrue(session.allowOrder(0, group), "operation " + group + " was refused inside the budget");
        }
        assertFalse(session.allowOrder(0, 17), "the eleventh operation of one second was let through");
    }

    @Test
    void theNinthPacketOfOneOperationIsRefused() {
        final GameSession session = new GameSession();
        for (int packet = 0; packet < 8; packet++) assertTrue(session.allowOrder(0, 3));

        assertFalse(session.allowOrder(0, 3), "one operation may not carry more packets than a selection can");
    }

    @Test
    void aRefusedOperationNeverBecomesAnAcceptedOneMidWay() {
        final GameSession session = new GameSession();
        for (int group = 0; group < 10; group++) assertTrue(session.allowOrder(0, group));

        // The eleventh operation is refused, and every further packet of it stays refused: it never
        // took a token, so there is nothing for its later packets to ride on
        assertFalse(session.allowOrder(0, 10));
        assertFalse(session.allowOrder(0, 10));
    }

    @Test
    void theBucketsRefillWithTheSecond() {
        final GameSession session = new GameSession();
        for (int group = 0; group < 10; group++) assertTrue(session.allowOrder(0, group));
        assertFalse(session.allowOrder(0, 10));

        assertTrue(session.allowOrder(1, 10), "the next second did not start with a full bucket");
    }

    @Test
    void theCameraAndSelectionBudgetsAreTenASecond() {
        final GameSession session = new GameSession();

        for (int i = 0; i < 10; i++) {
            assertTrue(session.allowCamera(0), "camera report " + i + " was dropped inside the budget");
            assertTrue(session.allowSelect(0), "selection " + i + " was dropped inside the budget");
        }
        assertFalse(session.allowCamera(0));
        assertFalse(session.allowSelect(0));

        assertTrue(session.allowCamera(1));
        assertTrue(session.allowSelect(1));
    }

    @Test
    void theBudgetsDoNotShareTokens() {
        final GameSession session = new GameSession();
        for (int i = 0; i < 10; i++) assertTrue(session.allowCamera(0));

        assertTrue(session.allowSelect(0), "a spent camera budget also refused a selection");
        assertTrue(session.allowOrder(0, 1), "a spent camera budget also refused an order");
    }

    @Test
    void threeSecondsOverTheThresholdTripTheFloodGate() {
        final GameSession session = new GameSession();

        assertEquals(-1, floodAt(session, 0, FLOODING), "the first second alone is not a flood");
        assertEquals(-1, floodAt(session, 1, FLOODING), "two seconds are not three");
        assertEquals(301, floodAt(session, 2, FLOODING),
                "the gate trips on the packet that takes the third second over the threshold");
    }

    @Test
    void theNormalPeakNeverTripsTheFloodGate() {
        final GameSession session = new GameSession();

        for (long second = 0; second < 60; second++) {
            assertEquals(-1, floodAt(session, second, PEAK), "the normal peak was punished at second " + second);
        }
    }

    @Test
    void justUnderTheThresholdNeverTripsTheFloodGate() {
        final GameSession session = new GameSession();

        for (long second = 0; second < 10; second++) {
            assertEquals(-1, floodAt(session, second, 300), "300 a second is not over 300 a second");
        }
    }

    @Test
    void aQuietSecondBreaksTheRun() {
        final GameSession session = new GameSession();
        assertEquals(-1, floodAt(session, 0, FLOODING));
        assertEquals(-1, floodAt(session, 1, FLOODING));

        // Second 2 carries no packet at all, so the run of consecutive seconds starts over
        assertEquals(-1, floodAt(session, 3, FLOODING), "a second with nothing in it still counted as flooding");
        assertEquals(-1, floodAt(session, 4, FLOODING));
        assertEquals(301, floodAt(session, 5, FLOODING));
    }

    /**
     * Sends one second's worth of payloads.
     *
     * @param session the session they arrive on
     * @param second  the second they arrive in
     * @param packets how many arrive
     * @return the ordinal of the payload the flood gate tripped on, or {@code -1} when it did not
     */
    private static int floodAt(GameSession session, long second, int packets) {
        for (int packet = 1; packet <= packets; packet++) {
            if (session.payloadFloods(second)) return packet;
        }
        return -1;
    }
}
