package io.github.bearisbug.synaxis.event.item;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.item.ItemAnimation;
import io.github.bearisbug.synaxis.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PlayerBeginItemUseEventTest {

    // The event only stores the player, a real one needs a whole connection
    private static final @Nullable Player NO_PLAYER = null;

    private static PlayerBeginItemUseEvent event(long duration) {
        return new PlayerBeginItemUseEvent(NO_PLAYER, PlayerHand.OFF, ItemStack.AIR,
                ItemAnimation.EAT, duration);
    }

    @Test
    public void theUseIsDescribedByWhatItWasBuiltWith() {
        var event = event(32L);
        assertSame(PlayerHand.OFF, event.getHand());
        assertSame(ItemStack.AIR, event.getItemStack());
        assertSame(ItemAnimation.EAT, event.getAnimation());
        assertEquals(32L, event.getItemUseDuration());
        assertFalse(event.isCancelled());
    }

    @Test
    public void theDurationCanBeStretchedByAListener() {
        var event = event(32L);
        event.setItemUseDuration(64L);
        assertEquals(64L, event.getItemUseDuration());

        event.setItemUseDuration(0L);
        assertEquals(0L, event.getItemUseDuration(), "An instant use is a valid duration");
    }

    @Test
    public void aNegativeDurationIsRefusedInsteadOfBeingStored() {
        var event = event(32L);
        assertThrows(IllegalArgumentException.class, () -> event.setItemUseDuration(-1L));
        assertEquals(32L, event.getItemUseDuration(), "The refused duration must not have been applied");
    }

    @Test
    public void theUseCanBeCancelled() {
        var event = event(32L);
        event.setCancelled(true);
        assertTrue(event.isCancelled());
    }
}
