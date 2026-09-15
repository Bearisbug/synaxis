package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.InventoryEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.inventory.Inventory;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientNameItemPacket;

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
