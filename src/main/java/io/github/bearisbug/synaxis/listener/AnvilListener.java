package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerAnvilInputEvent;
import io.github.bearisbug.synaxis.inventory.Inventory;
import io.github.bearisbug.synaxis.inventory.InventoryType;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientNameItemPacket;

public final class AnvilListener {

    public static void nameItemListener(ClientNameItemPacket packet, Player player) {
        if (!(player.getOpenInventory() instanceof Inventory openInventory))
            return;
        if (openInventory.getInventoryType() != InventoryType.ANVIL)
            return;

        player.process().eventHandler().call(new PlayerAnvilInputEvent(player, openInventory, packet.itemName()));
    }

    private AnvilListener() {
    }

}
