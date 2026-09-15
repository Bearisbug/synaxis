package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.book.EditBookEvent;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientEditBookPacket;
import io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils;

public class BookListener {

    public static void listener(ClientEditBookPacket packet, Player player) {
        int engineSlot = PlayerInventoryUtils.convertPlayerInventorySlotToEngineSlot(packet.slot());
        if (!PlayerInventoryUtils.isHotbarOrOffHandSlot(engineSlot)) return;

        final ItemStack itemStack = player.getInventory().getItemStack(engineSlot);
        player.process().eventHandler().call(new EditBookEvent(player, itemStack, packet.pages(), packet.title()));
    }

}
