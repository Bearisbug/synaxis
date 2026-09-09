package dev.gameengine.event.item;

import dev.gameengine.entity.Player;
import dev.gameengine.entity.PlayerHand;
import dev.gameengine.event.trait.ItemEvent;
import dev.gameengine.event.trait.PlayerInstanceEvent;
import dev.gameengine.item.ItemStack;

/**
 * Called when a player completely finishes using an item.
 *
 * <p>{@link #getUseDuration()} represents the total time spent using the item.</p>
 */
public class PlayerFinishItemUseEvent implements PlayerInstanceEvent, ItemEvent {
    private final Player player;
    private final PlayerHand hand;
    private final ItemStack itemStack;
    private final long useDuration;
    private boolean isRiptideSpinAttack = false;

    public PlayerFinishItemUseEvent(Player player, PlayerHand hand, ItemStack itemStack, long useDuration) {
        this.player = player;
        this.hand = hand;
        this.itemStack = itemStack;
        this.useDuration = useDuration;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public PlayerHand getHand() {
        return hand;
    }

    @Override
    public ItemStack getItemStack() {
        return itemStack;
    }

    public long getUseDuration() {
        return useDuration;
    }

    /**
     * True if this event will transition the player into a riptide spin attack.
     */
    public boolean isRiptideSpinAttack() {
        return isRiptideSpinAttack;
    }

    /**
     * True if this event will transition the player into a riptide spin attack.
     */
    public void setRiptideSpinAttack(boolean riptideSpinAttack) {
        isRiptideSpinAttack = riptideSpinAttack;
    }

}
