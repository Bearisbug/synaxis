package io.github.bearisbug.synaxis.network.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reason of a protocol error line ({@code docs/DESIGN.md} {@code REQ-017}): the innermost cause as
 * {@code Type: message}, at most 1,024 characters of it. The message of a decoding failure can quote
 * what the peer sent, and the peer decides how long that is.
 */
class ProtocolErrorCauseTest {

    @Test
    void theInnermostCauseIsReported() {
        final Exception error = new IllegalStateException("outer", new IllegalArgumentException("inner"));

        assertEquals("IllegalArgumentException: inner", PlayerSocketConnection.describeCause(error));
        assertEquals("IllegalArgumentException", PlayerSocketConnection.describeCause(new IllegalArgumentException()));
    }

    @Test
    void aLongCauseIsCutAfter1024Characters() {
        final String full = "IllegalArgumentException: " + "x".repeat(100_000);

        final String reason = PlayerSocketConnection.describeCause(new IllegalArgumentException("x".repeat(100_000)));

        assertEquals(full.substring(0, 1_024) + "...(" + full.length() + " chars)", reason);
        final String fits = "IllegalArgumentException: " + "x".repeat(1_024 - 26);
        assertEquals(1_024, fits.length());
        assertEquals(fits, PlayerSocketConnection.describeCause(new IllegalArgumentException("x".repeat(1_024 - 26))),
                "a reason of exactly 1,024 characters is kept whole");
    }
}
