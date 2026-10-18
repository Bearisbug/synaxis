package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.inventory.CreativeInventoryActionEvent;
import io.github.bearisbug.synaxis.inventory.PlayerInventory;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientCreativeInventoryActionPacket;
import io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils;

import java.util.Objects;

public final class CreativeInventoryActionListener {
    private static final int WINDOW_0_LAST_SLOT = 45; // window 0 slot 45 is the offhand

    public static void listener(ClientCreativeInventoryActionPacket packet, Player player) {
        if (player.getGameMode() != GameMode.CREATIVE) return;
        final short windowSlot = packet.slot();
        final ItemStack sentItem = packet.item();
        if (windowSlot == -1) {
            // Drop item
            CreativeInventoryActionEvent event = new CreativeInventoryActionEvent(player, windowSlot, sentItem);
            player.process().eventHandler().call(event);
            if (event.isCancelled()) return;
            player.dropItem(event.getClickedItem());
            return;
        }
        // Bounds check
        // 0 is crafting result inventory slot, ignore attempts to place into it
        if (windowSlot < 1 || windowSlot > WINDOW_0_LAST_SLOT) {
            return;
        }
        // Set item
        final int engineSlot = PlayerInventoryUtils.convertWindow0SlotToEngineSlot(windowSlot);
        PlayerInventory inventory = player.getInventory();

        CreativeInventoryActionEvent event = new CreativeInventoryActionEvent(player, engineSlot, sentItem);
        player.process().eventHandler().call(event);
        final ItemStack setItem = event.getClickedItem();
        final ItemStack previousItem = inventory.getItemStack(engineSlot);

        if (event.isCancelled()) {
            // Event is cancelled, keep the old item
            player.getInventory().sendSlotRefresh(engineSlot, previousItem);
            return;
        }

        final boolean isEqualToSentItem = Objects.equals(setItem, sentItem);

        if (Objects.equals(previousItem, sentItem) && isEqualToSentItem) {
            // Item is already present, ignore
            return;
        }

        inventory.setItemStack(engineSlot, setItem);

        if (!isEqualToSentItem) {
            player.getInventory().sendSlotRefresh(engineSlot, setItem);
        }
    }
}
