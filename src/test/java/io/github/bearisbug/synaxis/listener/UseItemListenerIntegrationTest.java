package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.EquipmentSlot;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.player.PlayerUseItemEvent;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientUseItemPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class UseItemListenerIntegrationTest {

    @Test
    void useItemNonSpecial(Env env) {
        // Any random item should not trigger any hand updates
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 41, 0));

        var useItemCollector = env.trackEvent(PlayerUseItemEvent.class, EventFilter.PLAYER, player);

        var itemStack = ItemStack.of(Material.DIAMOND);
        player.setItemInMainHand(itemStack);
        UseItemListener.useItemListener(new ClientUseItemPacket(PlayerHand.MAIN, 42, 0f, 0f), player);

        useItemCollector.assertSingle(event -> {
            assertEquals(PlayerHand.MAIN, event.getHand());
            assertEquals(itemStack, event.getItemStack());
            assertEquals(0, event.getItemUseTime());
        });
    }

    @Test
    void testEquipArmorToAir(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 41, 0));

        var boots = ItemStack.of(Material.DIAMOND_BOOTS);
        player.setItemInMainHand(boots);
        UseItemListener.useItemListener(new ClientUseItemPacket(PlayerHand.MAIN, 42, 0f, 0f), player);

        assertEquals(ItemStack.AIR, player.getItemInMainHand());
        assertEquals(boots, player.getEquipment(EquipmentSlot.BOOTS));
    }

    @Test
    void testEquipArmorSwap(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 41, 0));

        var oldBoots = ItemStack.of(Material.GOLDEN_BOOTS);
        player.setEquipment(EquipmentSlot.BOOTS, oldBoots);

        var boots = ItemStack.of(Material.DIAMOND_BOOTS);
        player.setItemInMainHand(boots);
        UseItemListener.useItemListener(new ClientUseItemPacket(PlayerHand.MAIN, 42, 0f, 0f), player);

        assertEquals(oldBoots, player.getItemInMainHand());
        assertEquals(boots, player.getEquipment(EquipmentSlot.BOOTS));
    }

    @Test
    void testDoNotEquipNonEquippableArmor(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 41, 0));

        var badBoots = ItemStack.of(Material.GOLDEN_BOOTS).without(DataComponents.EQUIPPABLE);
        player.setItemInMainHand(badBoots);
        UseItemListener.useItemListener(new ClientUseItemPacket(PlayerHand.MAIN, 42, 0f, 0f), player);

        assertEquals(badBoots, player.getItemInMainHand());
        assertEquals(ItemStack.AIR, player.getEquipment(EquipmentSlot.BOOTS));
    }

    @Test
    void testDoNotSwapNonSwappableArmor(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 41, 0));

        var oldBoots = ItemStack.of(Material.GOLDEN_BOOTS);
        player.setEquipment(EquipmentSlot.BOOTS, oldBoots);

        var boots = ItemStack.of(Material.DIAMOND_BOOTS).update(DataComponents.EQUIPPABLE, eq -> eq.withSwappable(false));
        player.setItemInMainHand(boots);
        UseItemListener.useItemListener(new ClientUseItemPacket(PlayerHand.MAIN, 42, 0f, 0f), player);

        assertEquals(boots, player.getItemInMainHand());
        assertEquals(oldBoots, player.getEquipment(EquipmentSlot.BOOTS));
    }

    @Test
    void testRotation(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 41, 0));
        player.refreshReceivedTeleportId(player.getLastSentTeleportId());

        assertEquals(new Pos(0, 41, 0), player.getPosition());
        UseItemListener.useItemListener(new ClientUseItemPacket(PlayerHand.MAIN, 42, 5f, 10f), player);
        assertEquals(new Pos(0, 41, 0, 5f, 10f), player.getPosition());
    }
}
