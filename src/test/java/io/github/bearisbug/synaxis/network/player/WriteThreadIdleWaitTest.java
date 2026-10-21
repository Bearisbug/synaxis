package io.github.bearisbug.synaxis.network.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How long the write thread sleeps on an empty queue, the default without
 * {@code synaxis.new-socket-write-lock}, at the tick rates {@code synaxis.tps} takes (1 to 1000).
 */
public class WriteThreadIdleWaitTest {

    @Test
    public void theWriteThreadSleepsHalfATickAndNeverZero() {
        assertEquals(25, PlayerSocketConnection.idleWaitMillis(20));
        assertEquals(1, PlayerSocketConnection.idleWaitMillis(500));
        // Half a tick rounds down to 0 there: a sleep of 0 spins the write thread of every connection
        assertEquals(1, PlayerSocketConnection.idleWaitMillis(1000));
    }
}
