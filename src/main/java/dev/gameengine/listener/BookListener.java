package dev.gameengine.listener;

import dev.gameengine.entity.Player;
import dev.gameengine.event.book.EditBookEvent;
import dev.gameengine.item.ItemStack;
import dev.gameengine.network.packet.client.play.ClientEditBookPacket;
import dev.gameengine.inventory.PlayerInventoryUtils;

public class BookListener {

    public static void listener(ClientEditBookPacket packet, Player player) {
        int minestomSlot = PlayerInventoryUtils.convertPlayerInventorySlotToMinestomSlot(packet.slot());
        if (!PlayerInventoryUtils.isHotbarOrOffHandSlot(minestomSlot)) return;

        final ItemStack itemStack = player.getInventory().getItemStack(minestomSlot);
        player.process().eventHandler().call(new EditBookEvent(player, itemStack, packet.pages(), packet.title()));
    }

}
