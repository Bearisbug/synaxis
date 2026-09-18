package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PlayerChangeHeldSlotEventTest {

    // The event only stores the player, a real one needs a whole connection
    private static final @Nullable Player NO_PLAYER = null;

    @Test
    public void theRequestedSlotsAreKeptAsGiven() {
        var event = new PlayerChangeHeldSlotEvent(NO_PLAYER, (byte) 0, (byte) 3);
        assertEquals((byte) 0, event.getOldSlot());
        assertEquals((byte) 3, event.getNewSlot());
        assertFalse(event.isCancelled());
    }

    @Test
    public void bothEndsOfTheHotbarAreAccepted() {
        var event = new PlayerChangeHeldSlotEvent(NO_PLAYER, (byte) 0, (byte) 3);
        event.setNewSlot((byte) 0);
        assertEquals((byte) 0, event.getNewSlot());
        event.setNewSlot((byte) 8);
        assertEquals((byte) 8, event.getNewSlot());
    }

    @Test
    public void aSlotOutsideTheHotbarIsRefused() {
        var event = new PlayerChangeHeldSlotEvent(NO_PLAYER, (byte) 0, (byte) 3);
        assertThrows(IllegalArgumentException.class, () -> event.setNewSlot((byte) 9));
        assertThrows(IllegalArgumentException.class, () -> event.setNewSlot((byte) -1));
        assertEquals((byte) 3, event.getNewSlot(), "A refused slot must not have been applied");
    }

    @Test
    public void theConstructorDoesNotValidateTheSlotTheClientSent() {
        // The packet listener is what rejects a bogus slot; the event still reports what arrived
        var event = new PlayerChangeHeldSlotEvent(NO_PLAYER, (byte) 0, (byte) 42);
        assertEquals((byte) 42, event.getNewSlot());
    }

    @Test
    public void theChangeCanBeCancelled() {
        var event = new PlayerChangeHeldSlotEvent(NO_PLAYER, (byte) 0, (byte) 3);
        event.setCancelled(true);
        assertTrue(event.isCancelled());
    }
}
