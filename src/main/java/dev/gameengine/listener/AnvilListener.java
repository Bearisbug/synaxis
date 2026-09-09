package dev.gameengine.listener;

import dev.gameengine.entity.Player;
import dev.gameengine.event.EventDispatcher;
import dev.gameengine.event.player.PlayerAnvilInputEvent;
import dev.gameengine.inventory.Inventory;
import dev.gameengine.inventory.InventoryType;
import dev.gameengine.network.packet.client.play.ClientNameItemPacket;

public final class AnvilListener {

    public static void nameItemListener(ClientNameItemPacket packet, Player player) {
        if (!(player.getOpenInventory() instanceof Inventory openInventory))
            return;
        if (openInventory.getInventoryType() != InventoryType.ANVIL)
            return;

        EventDispatcher.call(new PlayerAnvilInputEvent(player, openInventory, packet.itemName()));
    }

    private AnvilListener() {
    }

}
