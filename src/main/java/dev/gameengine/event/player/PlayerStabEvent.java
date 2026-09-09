package dev.gameengine.event.player;

import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.PlayerInstanceEvent;
import dev.gameengine.item.ItemStack;

/**
 * Called when a player attempts to use a stab attack on an item with the {@link dev.gameengine.item.component.PiercingWeapon} enchantment.
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
