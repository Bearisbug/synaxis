package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.inventory.InventoryBundleItemSelectEvent;
import io.github.bearisbug.synaxis.event.inventory.InventoryButtonClickEvent;
import io.github.bearisbug.synaxis.event.inventory.InventoryPreClickEvent;
import io.github.bearisbug.synaxis.inventory.AbstractInventory;
import io.github.bearisbug.synaxis.inventory.click.Click;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPongPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientClickWindowButtonPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientClickWindowPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientCloseWindowPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSelectBundleItemPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetCursorItemPacket;
import io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils;
import org.jetbrains.annotations.Nullable;

public class WindowListener {

    public static void clickWindowListener(ClientClickWindowPacket packet, Player player) {
        final int windowId = packet.windowId();
        final boolean playerInventory = windowId == 0;
        final AbstractInventory inventory = playerInventory ? player.getInventory() : player.getOpenInventory();

        // Prevent some invalid packets
        if (inventory == null || packet.slot() == -1) return;

        // A click carrying the id of an inventory that is no longer open must not be applied to the one that is
        if (!playerInventory && windowId != (int) inventory.getWindowId()) {
            inventory.update(player);
            return;
        }

        // Process the click
        @Nullable Integer size = playerInventory ? null : inventory.getSize();
        Click click = player.getClickPreprocessor().processClick(packet, size);

        final boolean successful = click == null || applyClick(player, inventory, click, size);

        // Prevent ghost item when the click is cancelled
        if (!successful) {
            player.getInventory().update(player);
            if (!playerInventory) {
                inventory.update(player);
            }
        }

        // Resync in case the client sent item does not match what we think it should be.
        ItemStack cursorItem = player.getInventory().getCursorItem();
        if (!ItemStack.Hash.of(cursorItem, player.process()).equals(packet.clickedItem()))
            player.sendPacket(new SetCursorItemPacket(cursorItem));
    }

    /**
     * Calls {@link InventoryPreClickEvent} for a click and applies the click the event leaves.
     *
     * @return false if the click is refused: a creative click outside creative mode, a cancelled
     * event, or a listener that closed or replaced the inventory the click was aimed at
     */
    private static boolean applyClick(Player player, AbstractInventory inventory, Click click, @Nullable Integer size) {
        // Disallow creative clicks when not in creative
        boolean isNotCreative = player.getGameMode() != GameMode.CREATIVE;
        if (isNotCreative && player.getClickPreprocessor().isCreativeClick(click, !player.getInventory().getCursorItem().isAir())) {
            return false;
        }

        // Remember what was open so that a listener closing (or replacing) it cancels the click
        final AbstractInventory openBeforeEvent = player.getOpenInventory();

        Click.Window window = Click.toWindow(click, size);
        // Call InventoryPreClickEvent
        InventoryPreClickEvent inventoryPreClickEvent = new InventoryPreClickEvent(window.inOpened() ? inventory : player.getInventory(), player, window.click());
        player.process().eventHandler().call(inventoryPreClickEvent);

        // Cancel the click if a listener closed or replaced the inventory it was aimed at
        if (player.getOpenInventory() != openBeforeEvent) return false;
        // Cancel it if the event is cancelled
        if (inventoryPreClickEvent.isCancelled()) return false;
        return inventory.handleClick(player, Click.fromWindow(new Click.Window(window.inOpened(), inventoryPreClickEvent.getClick()), size));
    }

    public static void pong(ClientPongPacket packet, Player player) {
        // Empty
    }

    public static void closeWindowListener(ClientCloseWindowPacket packet, Player player) {
        player.closeInventory(true, (byte) packet.windowId());
    }

    public static void inventoryButtonClickListener(ClientClickWindowButtonPacket packet, Player player) {
        AbstractInventory inventory = player.getOpenInventory();

        // Can't press a button if the inventory is not open
        if (inventory == null) return;

        // Can't press a button if the inventory is different from the packet's window id
        if (packet.windowId() != (int) inventory.getWindowId()) return;

        player.process().eventHandler().call(new InventoryButtonClickEvent(player, inventory, packet.buttonId()));
    }

    public static void selectBundleItemListener(ClientSelectBundleItemPacket packet, Player player) {
        // Both fields are unvalidated client input: an invalid packet is dropped, throwing here would only
        // print a stack trace on the tick thread for every packet the client cares to send.
        final int selectedItemIndex = packet.selectedIndex();
        if (selectedItemIndex < -1) return;

        final int rawSlot = packet.slot();
        if (rawSlot < 0) return;

        final AbstractInventory openInventory = player.getOpenInventory();
        final AbstractInventory targetInventory;
        final int translatedSlot;

        if (openInventory != null && rawSlot < openInventory.getSize()) {
            targetInventory = openInventory;
            translatedSlot = rawSlot;
        } else {
            translatedSlot = openInventory == null ? PlayerInventoryUtils.convertWindow0SlotToEngineSlot(rawSlot)
                    : PlayerInventoryUtils.convertWindowSlotToEngineSlot(rawSlot, openInventory.getSize());
            if (translatedSlot >= player.getInventory().getSize() || translatedSlot < 0) return;
            targetInventory = player.getInventory();
        }

        InventoryBundleItemSelectEvent event = new InventoryBundleItemSelectEvent(
                player,
                targetInventory,
                translatedSlot,
                selectedItemIndex
        );
        player.process().eventHandler().call(event);
    }
}
