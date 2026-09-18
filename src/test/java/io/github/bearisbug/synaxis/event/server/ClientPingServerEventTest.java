package io.github.bearisbug.synaxis.event.server;

import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClientPingServerEventTest {

    private static ClientPingServerEvent event(long payload) {
        final @Nullable PlayerConnection connection = null;
        return new ClientPingServerEvent(connection, payload);
    }

    @Test
    public void aPingStartsWithoutDelayAndUncancelled() {
        var event = event(42L);
        assertEquals(42L, event.getPayload());
        assertEquals(Duration.ZERO, event.getDelay());
        assertFalse(event.isCancelled());
    }

    @Test
    public void addDelayAccumulatesWhileSetDelayReplaces() {
        var event = event(1L);
        event.addDelay(Duration.ofMillis(100));
        event.addDelay(Duration.ofMillis(50));
        assertEquals(Duration.ofMillis(150), event.getDelay());

        event.setDelay(Duration.ofMillis(10));
        assertEquals(Duration.ofMillis(10), event.getDelay());
    }

    @Test
    public void noDelayResetsWhateverWasAccumulated() {
        var event = event(1L);
        event.addDelay(Duration.ofSeconds(5));
        event.noDelay();
        assertEquals(Duration.ZERO, event.getDelay());
    }

    @Test
    public void theExplicitDelayConstructorKeepsItsValue() {
        final @Nullable PlayerConnection connection = null;
        var event = new ClientPingServerEvent(connection, 7L, Duration.ofMillis(250));
        assertEquals(Duration.ofMillis(250), event.getDelay());
        assertSame(connection, event.getConnection());
    }

    @Test
    public void thePayloadCanBeAnsweredWithAnotherValue() {
        var event = event(1L);
        event.setPayload(9L);
        assertEquals(9L, event.getPayload());

        event.setCancelled(true);
        assertTrue(event.isCancelled());
    }
}
