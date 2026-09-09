package dev.gameengine.event.player;

import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.InventoryEvent;
import dev.gameengine.event.trait.PlayerInstanceEvent;
import dev.gameengine.inventory.Inventory;
import dev.gameengine.network.packet.client.play.ClientNameItemPacket;

/**
 * Called every time a {@link Player} types a letter in an anvil GUI.
 *
 * @see ClientNameItemPacket
 */
public class PlayerAnvilInputEvent implements PlayerInstanceEvent, InventoryEvent {

    private final Player player;
    private final Inventory inventory;
    private final String input;

    public PlayerAnvilInputEvent(Player player, Inventory inventory, String input) {
        this.player = player;
        this.inventory = inventory;
        this.input = input;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public String getInput() {
        return input;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

}
