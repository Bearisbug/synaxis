package io.github.bearisbug.synaxis.item;

import net.kyori.adventure.nbt.BinaryTag;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.inventory.Inventory;
import io.github.bearisbug.synaxis.inventory.InventoryType;
import io.github.bearisbug.synaxis.item.component.EnchantmentList;
import io.github.bearisbug.synaxis.item.component.TooltipDisplay;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.BuiltinRegistries;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RegistriesTest
public class ItemStackRegistriesTest {

    @Test
    void resetRevertsToMaterialDefault() {
        ItemStack apple = ItemStack.of(Material.APPLE).without(DataComponents.FOOD);

        assertFalse(apple.has(DataComponents.FOOD));
        assertSame(apple, apple.reset(DataComponents.REPAIR_COST));
        assertTrue(apple.reset(DataComponents.FOOD).has(DataComponents.FOOD));
    }

    @Test
    void componentsReturnsResolvedView() {
        ItemStack item = ItemStack.of(Material.APPLE)
                .without(DataComponents.FOOD)
                .with(DataComponents.REPAIR_COST, 5);

        assertFalse(item.components().has(DataComponents.FOOD));
        assertEquals(5, item.components().get(DataComponents.REPAIR_COST));
        assertNull(item.componentPatch().get(DataComponents.FOOD));
    }

    @Test
    void withoutExtraTooltipHidesEveryDetailLineAndKeepsWhatWasHidden() {
        final ItemStack sword = ItemStack.of(Material.DIAMOND_SWORD)
                .with(DataComponents.ENCHANTMENTS, new EnchantmentList(RegistryKey.unsafeOf("minecraft:sharpness"), 5))
                .with(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, Set.of(DataComponents.LORE)))
                .withoutExtraTooltip();

        final TooltipDisplay display = sword.get(DataComponents.TOOLTIP_DISPLAY);
        assertNotNull(display);
        assertTrue(display.hideTooltip(), "the hidden tooltip was shown again");
        assertTrue(display.hiddenComponents().containsAll(Set.of(DataComponents.LORE, DataComponents.ENCHANTMENTS,
                DataComponents.DYED_COLOR, DataComponents.TRIM, DataComponents.ATTRIBUTE_MODIFIERS)), display.toString());
        assertFalse(display.hiddenComponents().contains(DataComponents.CUSTOM_NAME));
        assertFalse(display.hiddenComponents().contains(DataComponents.ITEM_NAME));

        final TooltipDisplay plain = ItemStack.of(Material.STONE).withoutExtraTooltip().get(DataComponents.TOOLTIP_DISPLAY);
        assertNotNull(plain);
        assertFalse(plain.hideTooltip());
        assertEquals(33, plain.hiddenComponents().size(), plain.toString());
        assertFalse(plain.hiddenComponents().contains(DataComponents.LORE));
    }

    @Test
    void damageFollowsTheRulesOfDamageableItems() {
        final ItemStack sword = ItemStack.of(Material.DIAMOND_SWORD);
        final int maxDamage = sword.get(DataComponents.MAX_DAMAGE, 0);

        final ItemStack unbreakable = sword.with(DataComponents.UNBREAKABLE);
        assertSame(unbreakable, unbreakable.damage(10_000));
        assertEquals(10, sword.damage(10).get(DataComponents.DAMAGE));
        assertSame(ItemStack.AIR, sword.damage(maxDamage));
        assertSame(ItemStack.AIR, sword.damage(10).damage(Integer.MAX_VALUE));
        assertEquals(0, sword.damage(3).damage(-5).get(DataComponents.DAMAGE));

        final ItemStack stone = ItemStack.of(Material.STONE).with(DataComponents.DAMAGE, 0);
        assertSame(stone, stone.damage(3));
    }

    @Test
    void anItemWithoutAMaxStackSizeStacksToOne() {
        assertEquals(1, ItemStack.of(Material.STONE).without(DataComponents.MAX_STACK_SIZE).maxStackSize());
        assertEquals(64, ItemStack.of(Material.STONE).maxStackSize());
        assertEquals(64, Material.STONE.maxStackSize());

        // The client stacks it to 1, so an inventory must not merge two of them
        final ItemStack stone = ItemStack.of(Material.STONE).without(DataComponents.MAX_STACK_SIZE);
        final Inventory inventory = new Inventory(InventoryType.CHEST_1_ROW, "title");
        assertTrue(inventory.addItemStack(stone));
        assertTrue(inventory.addItemStack(stone));
        assertEquals(1, inventory.getItemStack(0).amount());
        assertEquals(1, inventory.getItemStack(1).amount());
    }

    @Test
    void updateTakesAnImplicitlyTypedLambda() {
        final ItemStack boots = ItemStack.of(Material.DIAMOND_BOOTS)
                .update(DataComponents.EQUIPPABLE, equippable -> equippable.withSwappable(false));
        final var equippable = boots.get(DataComponents.EQUIPPABLE);
        assertNotNull(equippable);
        assertFalse(equippable.swappable());

        final ItemStack stone = ItemStack.of(Material.STONE);
        assertSame(stone, stone.update(DataComponents.EQUIPPABLE, e -> e.withSwappable(false)));
    }

    // 26.2 writes jukebox_playable as a single holder: there is no either flag in front of it
    @Test
    void jukeboxPlayableIsASingleHolder(Registries registries) {
        final RegistryKey<BinaryTag> cat = RegistryKey.unsafeOf("minecraft:cat");
        final int id = registries.opaque(BuiltinRegistries.JUKEBOX_SONG).getId(cat);

        assertArrayEquals(NetworkBuffer.makeArray(buffer -> buffer.write(NetworkBuffer.VAR_INT, id + 1)),
                NetworkBuffer.makeArray(buffer -> DataComponents.JUKEBOX_PLAYABLE.write(buffer, cat), registries));

        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(16, registries);
        buffer.write(NetworkBuffer.VAR_INT, id + 1);
        assertEquals(cat, DataComponents.JUKEBOX_PLAYABLE.read(buffer));
    }
}
