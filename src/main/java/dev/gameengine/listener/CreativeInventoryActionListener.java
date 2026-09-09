package dev.gameengine.listener;

import dev.gameengine.entity.GameMode;
import dev.gameengine.entity.Player;
import dev.gameengine.event.EventDispatcher;
import dev.gameengine.event.inventory.CreativeInventoryActionEvent;
import dev.gameengine.inventory.PlayerInventory;
import dev.gameengine.item.ItemStack;
import dev.gameengine.network.packet.client.play.ClientCreativeInventoryActionPacket;
import dev.gameengine.utils.inventory.PlayerInventoryUtils;

import java.util.Objects;

public final class CreativeInventoryActionListener {
    public static void listener(ClientCreativeInventoryActionPacket packet, Player player) {
        if (player.getGameMode() != GameMode.CREATIVE) return;
        short slot = packet.slot();
        final ItemStack sentItem = packet.item();
        if (slot == -1) {
            // Drop item
            CreativeInventoryActionEvent event = new CreativeInventoryActionEvent(player, slot, sentItem);
            EventDispatcher.call(event);
            if (event.isCancelled()) return;
            player.dropItem(event.getClickedItem());
            return;
        }
        // Bounds check
        // 0 is crafting result inventory slot, ignore attempts to place into it
        if (slot < 1 || slot > PlayerInventoryUtils.OFFHAND_SLOT) {
            return;
        }
        // Set item
        slot = (short) PlayerInventoryUtils.convertWindow0SlotToMinestomSlot(slot);
        PlayerInventory inventory = player.getInventory();

        CreativeInventoryActionEvent event = new CreativeInventoryActionEvent(player, slot, sentItem);
        EventDispatcher.call(event);
        final ItemStack setItem = event.getClickedItem();
        final ItemStack previousItem = inventory.getItemStack(slot);

        if (event.isCancelled()) {
            // Event is cancelled, keep the old item
            player.getInventory().sendSlotRefresh(slot, previousItem);
            return;
        }

        final boolean isEqualToSentItem = Objects.equals(setItem, sentItem);

        if (Objects.equals(previousItem, sentItem) && isEqualToSentItem) {
            // Item is already present, ignore
            return;
        }

        inventory.setItemStack(slot, setItem);

        if (!isEqualToSentItem) {
            player.getInventory().sendSlotRefresh(slot, setItem);
        }
    }
}
