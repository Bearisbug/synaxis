package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.item.ItemStack;

/**
 * Called when a player whose main hand item has the {@link DataComponents#PIERCING_WEAPON} component starts a stab
 * attack. Not cancellable: the engine deals no damage for it.
 */
public class PlayerStabEvent implements PlayerInstanceEvent {
    private final Player player;
    private final ItemStack itemStack;

    public PlayerStabEvent(Player player) {
        this.player = player;
        this.itemStack = player.getItemInMainHand();
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    /**
     * Gets the item which the player attacked with.
     *
     * @return the item in main hand when the event was called
     */
    public ItemStack getItemStack() {
        return itemStack;
    }
}
