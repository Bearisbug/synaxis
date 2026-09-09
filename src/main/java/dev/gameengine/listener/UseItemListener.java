package dev.gameengine.listener;

import dev.gameengine.component.DataComponents;
import dev.gameengine.entity.Player;
import dev.gameengine.entity.PlayerHand;
import dev.gameengine.event.EventDispatcher;
import dev.gameengine.event.item.PlayerBeginItemUseEvent;
import dev.gameengine.event.player.PlayerUseItemEvent;
import dev.gameengine.inventory.PlayerInventory;
import dev.gameengine.item.ItemAnimation;
import dev.gameengine.item.ItemStack;
import dev.gameengine.item.Material;
import dev.gameengine.item.component.Consumable;
import dev.gameengine.item.component.Equippable;
import dev.gameengine.network.packet.client.play.ClientUseItemPacket;
import dev.gameengine.network.packet.server.play.AcknowledgeBlockChangePacket;

public class UseItemListener {

    public static void useItemListener(ClientUseItemPacket packet, Player player) {
        PlayerPositionListener.playerRotation(player, packet.yaw(), packet.pitch());

        final PlayerHand hand = packet.hand();
        final ItemStack itemStack = player.getItemInHand(hand);
        final Material material = itemStack.material();
        final Consumable consumable = itemStack.get(DataComponents.CONSUMABLE);

        // The following item animations and use item times come from vanilla.
        // These items do not yet use components, but hopefully they will in the future
        // and this behavior can be removed.
        long useItemTime = 0;
        ItemAnimation useAnimation = ItemAnimation.NONE;
        if (material == Material.BOW) {
            useItemTime = 72000;
            useAnimation = ItemAnimation.BOW;
        } else if (material == Material.CROSSBOW) {
            // The crossbow has a min charge time dependent on quick charge, but to the
            // client they can hold it forever
            useItemTime = 7200;
            useAnimation = ItemAnimation.CROSSBOW;
        } else if (itemStack.has(DataComponents.BLOCKS_ATTACKS)) {
            useItemTime = 72000;
            useAnimation = ItemAnimation.BLOCK;
        } else if (material == Material.TRIDENT) {
            useItemTime = 72000;
            useAnimation = ItemAnimation.TRIDENT;
        } else if (material == Material.SPYGLASS) {
            useItemTime = 1200;
            useAnimation = ItemAnimation.SPYGLASS;
        } else if (material == Material.BRUSH) {
            useItemTime = 200;
            useAnimation = ItemAnimation.BRUSH;
        } else if (material.name().contains("bundle")) {
            // Why is a bundle usable???
            useItemTime = 200;
            useAnimation = ItemAnimation.BUNDLE;
        } else if (consumable != null) {
            useItemTime = consumable.consumeTicks();
            useAnimation = consumable.animation();
        } else if (itemStack.has(DataComponents.KINETIC_WEAPON)) {
            useItemTime = 72000;
            useAnimation = ItemAnimation.SPEAR;
        }

        boolean usingMainHand = player.getItemUseHand() == PlayerHand.MAIN && hand == PlayerHand.OFF;
        PlayerUseItemEvent useItemEvent = new PlayerUseItemEvent(player, hand, itemStack,
                usingMainHand ? 0 : useItemTime);
        EventDispatcher.call(useItemEvent);

        player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
        final PlayerInventory playerInventory = player.getInventory();
        if (useItemEvent.isCancelled()) {
            playerInventory.update();
            return;
        }

        useItemTime = useItemEvent.getItemUseTime();
        if (useItemTime != 0) {
            final PlayerBeginItemUseEvent beginUseEvent = new PlayerBeginItemUseEvent(player, hand, itemStack, useAnimation, useItemTime);
            EventDispatcher.callCancellable(beginUseEvent, () -> {
                if (beginUseEvent.getItemUseDuration() <= 0) return;

                player.refreshItemUse(hand, beginUseEvent.getItemUseDuration());
                player.refreshActiveHand(true, hand == PlayerHand.OFF, false);
            });

            return; // Do not also swap after use
        }

        // If the item was not usable, we can try to do an equipment swap with it.
        final Equippable equippable = itemStack.get(DataComponents.EQUIPPABLE);
        if (equippable != null && equippable.swappable() && equippable.slot().armorSlot() > 0) {
            final ItemStack currentlyEquipped = player.getEquipment(equippable.slot());
            player.setEquipment(equippable.slot(), itemStack);
            player.setItemInHand(hand, currentlyEquipped);
        }
    }

}
