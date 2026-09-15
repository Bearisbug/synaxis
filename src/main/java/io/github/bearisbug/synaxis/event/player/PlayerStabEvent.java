package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.item.ItemStack;

/**
 * Called when a player attempts to use a stab attack on an item with the {@link io.github.bearisbug.synaxis.item.component.PiercingWeapon} enchantment.
 */
public class PlayerStabEvent implements PlayerInstanceEvent {
    private final Player player;

    public PlayerStabEvent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    /**
     * Gets the item which the player attacked with.
     *
     * @return the item in main hand
     */
    public ItemStack getItemStack() {
        return player.getItemInMainHand();
    }
}
