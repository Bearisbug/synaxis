package dev.gameengine.listener;

import dev.gameengine.entity.GameMode;
import dev.gameengine.entity.Player;
import dev.gameengine.event.inventory.InventoryBundleItemSelectEvent;
import dev.gameengine.event.inventory.InventoryButtonClickEvent;
import dev.gameengine.event.inventory.InventoryPreClickEvent;
import dev.gameengine.inventory.AbstractInventory;
import dev.gameengine.inventory.click.Click;
import dev.gameengine.item.ItemStack;
import dev.gameengine.network.packet.client.common.ClientPongPacket;
import dev.gameengine.network.packet.client.play.ClientClickWindowButtonPacket;
import dev.gameengine.network.packet.client.play.ClientClickWindowPacket;
import dev.gameengine.network.packet.client.play.ClientCloseWindowPacket;
import dev.gameengine.network.packet.client.play.ClientSelectBundleItemPacket;
import dev.gameengine.network.packet.server.play.SetCursorItemPacket;
import dev.gameengine.utils.inventory.PlayerInventoryUtils;
import org.jetbrains.annotations.Nullable;

public class WindowListener {

    @SuppressWarnings("LabelledBreakTarget")
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

        boolean successful = true;
        check: if (click != null) {
            // Disallow creative clicks when not in creative
            boolean isNotCreative = player.getGameMode() != GameMode.CREATIVE;
            if (isNotCreative && player.getClickPreprocessor().isCreativeClick(click, !player.getInventory().getCursorItem().isAir())) {
                successful = false;
                break check;
            }

            // Remember what was open so that a listener closing (or replacing) it cancels the click
            final AbstractInventory openBeforeEvent = player.getOpenInventory();

            Click.Window window = Click.toWindow(click, size);
            // Call InventoryPreClickEvent
            InventoryPreClickEvent inventoryPreClickEvent = new InventoryPreClickEvent(window.inOpened() ? inventory : player.getInventory(), player, window.click());
            player.process().eventHandler().call(inventoryPreClickEvent);

            click = Click.fromWindow(new Click.Window(window.inOpened(), inventoryPreClickEvent.getClick()), size);

            if (player.getOpenInventory() != openBeforeEvent) {
                // Cancel the click if a listener closed or replaced the inventory it was aimed at
                successful = false;
            } else if (inventoryPreClickEvent.isCancelled()) {
                // Cancel it if the event is cancelled and we haven't already done that
                successful = false;
            } else {
                successful = inventory.handleClick(player, click);
            }
        }

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
        final int selectedItemIndex = packet.selectedIndex();
        if (selectedItemIndex < -1) {
            throw new IllegalArgumentException("Selected item index cannot be less than -1: " + selectedItemIndex);
        }

        final int rawSlot = packet.slot();
        if (rawSlot < 0) {
            throw new IllegalArgumentException("Slot index cannot be negative: " + rawSlot);
        }

        final AbstractInventory openInventory = player.getOpenInventory();
        final AbstractInventory targetInventory;
        final int translatedSlot;

        if (openInventory == null) {
            translatedSlot = PlayerInventoryUtils.convertWindow0SlotToMinestomSlot(rawSlot);
            if (translatedSlot > player.getInventory().getSize() || translatedSlot < 0) {
                throw new IllegalArgumentException("Slot index " + rawSlot + " is out of bounds for player inventory");
            }
            targetInventory = player.getInventory();
        } else {
            final int containerSize = openInventory.getSize();
            if (rawSlot < containerSize) {
                targetInventory = openInventory;
                translatedSlot = rawSlot;
            } else {
                translatedSlot = PlayerInventoryUtils.convertWindowSlotToMinestomSlot(rawSlot, containerSize);
                if (translatedSlot > player.getInventory().getSize() || translatedSlot < 0) {
                    throw new IllegalArgumentException("Slot index " + rawSlot + " is out of bounds for player inventory");
                }
                targetInventory = player.getInventory();
            }
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
