package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text a window full of items has to have translated is that of every item and of the cursor,
 * the same components as the packets that carry a single item.
 */
@RegistriesTest
public class WindowItemsPacketRegistriesTest {

    @Test
    public void theComponentsAreThoseOfEachItemThenOfTheCursor() {
        final List<ItemStack> items = List.of(named(), ItemStack.of(Material.STONE));
        final ItemStack carried = carried();

        final List<Component> expected = textComponents(items, carried);
        assertTrue(expected.containsAll(List.of(Component.text("lore 1"), Component.text("lore 2"),
                Component.text("custom name"), Component.text("item name"))), expected::toString);
        assertEquals(expected, new WindowItemsPacket(1, 2, items, carried).components());
    }

    @Test
    public void theOperatorReplacesTheComponentsOfEachItemAndOfTheCursor() {
        final List<ItemStack> items = List.of(named(), ItemStack.of(Material.STONE));
        final ItemStack carried = carried();
        final var packet = (WindowItemsPacket) new WindowItemsPacket(1, 2, items, carried)
                .copyWithOperator(_ -> Component.text("X"));

        final int count = textComponents(items, carried).size();
        assertEquals(Collections.nCopies(count, Component.text("X")), textComponents(packet.items(), packet.carriedItem()));
        assertEquals(1, packet.windowId());
        assertEquals(2, packet.stateId());
    }

    private static List<Component> textComponents(List<ItemStack> items, ItemStack carried) {
        final List<Component> components = new ArrayList<>();
        for (ItemStack item : items) components.addAll(ItemStack.textComponents(item));
        components.addAll(ItemStack.textComponents(carried));
        return components;
    }

    private static ItemStack named() {
        return ItemStack.of(Material.DIAMOND)
                .with(DataComponents.LORE, List.of(Component.text("lore 1"), Component.text("lore 2")))
                .with(DataComponents.CUSTOM_NAME, Component.text("custom name"));
    }

    private static ItemStack carried() {
        return ItemStack.of(Material.STICK).with(DataComponents.ITEM_NAME, Component.text("item name"));
    }
}
