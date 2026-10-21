package io.github.bearisbug.synaxis.item.component;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.ItemStackTemplate;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryTranscoder;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Values a 26.2 client cannot decode are refused where the component is made, not on the writer thread of every
 * client that gets the item.
 */
@RegistriesTest
public class ItemComponentLimitsRegistriesTest {

    @Test
    void identifierComponentsRefuseWhatIsNotANamespacedId() {
        final ItemStack stone = ItemStack.of(Material.STONE);

        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> stone.withItemModel("Synaxis:Unit"));
        assertEquals("minecraft:item_model must be a namespaced id such as synaxis:unit/archer (a-z 0-9 . _ - /), got: Synaxis:Unit",
                error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ItemStack.builder(Material.STONE).itemModel("units/Archer"));
        assertThrows(IllegalArgumentException.class, () -> stone.with(DataComponents.TOOLTIP_STYLE, "my style"));
        assertThrows(IllegalArgumentException.class, () -> stone.with(DataComponents.NOTE_BLOCK_SOUND, "Note"));
        assertEquals("synaxis:unit/archer", stone.withItemModel("synaxis:unit/archer").get(DataComponents.ITEM_MODEL));

        assertThrows(IllegalArgumentException.class, () -> new UseCooldown(1f, "Fire Ball"));
        assertDoesNotThrow(() -> new UseCooldown(1f, "synaxis:fire_ball"));
        assertDoesNotThrow(() -> new UseCooldown(1f, null));

        final Equippable helmet = ItemStack.of(Material.DIAMOND_HELMET).get(DataComponents.EQUIPPABLE);
        assertThrows(IllegalArgumentException.class, () -> helmet.withAssetId("My Armor"));
        assertThrows(IllegalArgumentException.class, () -> helmet.withCameraOverlay("misc/Pumpkin Blur"));
        assertDoesNotThrow(() -> helmet.withAssetId("synaxis:knight").withCameraOverlay(null));
    }

    @Test
    void loreHoldsAtMost256Lines() {
        final List<Component> lines = Collections.nCopies(257, Component.text("line"));
        final ItemStack stone = ItemStack.of(Material.STONE);

        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> stone.withLore(lines));
        assertEquals("minecraft:lore holds at most 256 lines, got 257", error.getMessage());
        assertDoesNotThrow(() -> stone.withLore(lines.subList(0, 256)));
    }

    /**
     * A 26.2 client reads at most 256 slots of a container; the codec stores no more either.
     */
    @Test
    void containersHoldAtMost256Slots(Registries registries) {
        final List<ItemStack> slots = Collections.nCopies(257, ItemStack.of(Material.STONE));
        final ItemStack box = ItemStack.of(Material.SHULKER_BOX);

        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> box.with(DataComponents.CONTAINER, slots));
        assertEquals("minecraft:container holds at most 256 entries, got 257", error.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> ItemStack.builder(Material.SHULKER_BOX).set(DataComponents.CONTAINER, slots));

        final ItemStack full = box.with(DataComponents.CONTAINER, slots.subList(0, 256));
        final var coder = new RegistryTranscoder<>(Transcoder.NBT, registries);
        assertEquals(full, assertOk(ItemStack.CODEC.decode(coder, assertOk(ItemStack.CODEC.encode(coder, full)))));
    }

    @Test
    void itemListsAndRecipesHoldAtMost32767Entries() {
        final List<ItemStack> items = Collections.nCopies(Short.MAX_VALUE + 1, ItemStack.of(Material.ARROW));

        final IllegalArgumentException bundle = assertThrows(IllegalArgumentException.class,
                () -> ItemStack.of(Material.BUNDLE).with(DataComponents.BUNDLE_CONTENTS, items));
        assertEquals("minecraft:bundle_contents holds at most 32767 entries, got 32768", bundle.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ItemStack.of(Material.KNOWLEDGE_BOOK)
                .with(DataComponents.RECIPES, Collections.nCopies(Short.MAX_VALUE + 1, "minecraft:stick")));
        assertDoesNotThrow(() -> ItemStack.of(Material.KNOWLEDGE_BOOK)
                .with(DataComponents.RECIPES, Collections.nCopies(Short.MAX_VALUE, "minecraft:stick")));
    }

    /**
     * A 26.2 client reads at most 1,024 charged projectiles; 26.2 builds and stores no more either.
     */
    @Test
    void chargedProjectilesHoldAtMost1024Items(Registries registries) {
        final List<ItemStack> arrows = Collections.nCopies(1025, ItemStack.of(Material.ARROW));
        final ItemStack crossbow = ItemStack.of(Material.CROSSBOW);

        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> crossbow.with(DataComponents.CHARGED_PROJECTILES, arrows));
        assertEquals("minecraft:charged_projectiles holds at most 1024 entries, got 1025", error.getMessage());
        final ItemStack loaded = crossbow.with(DataComponents.CHARGED_PROJECTILES, arrows.subList(0, 1024));
        final var coder = new RegistryTranscoder<>(Transcoder.NBT, registries);
        assertEquals(loaded, assertOk(ItemStack.CODEC.decode(coder, assertOk(ItemStack.CODEC.encode(coder, loaded)))));

        assertThrows(IllegalArgumentException.class, () -> NetworkBuffer.makeArray(
                buffer -> DataComponents.CHARGED_PROJECTILES.write(buffer, arrows), registries));
        final BinaryTag stored = assertOk(ItemStackTemplate.CODEC.list().encode(coder, arrows));
        assertInstanceOf(Result.Error.class, DataComponents.CHARGED_PROJECTILES.decode(coder, stored));
    }

    @Test
    void writtenBookTitlesHoldAtMost32Characters() {
        assertThrows(IllegalArgumentException.class,
                () -> new WrittenBookContent("The Chronicles of the Ancient Kingdom", "author", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WrittenBookContent(
                new FilteredText<>("short", "x".repeat(33)), "author", 0, List.of(), true));
        assertDoesNotThrow(() -> new WrittenBookContent("x".repeat(32), "author", List.of()));
    }

    @Test
    void booksAndQuillsHoldAtMost100PagesOf1024Characters() {
        final FilteredText<String> page = new FilteredText<>("text", null);

        assertThrows(IllegalArgumentException.class, () -> new WritableBookContent(Collections.nCopies(101, page)));
        assertDoesNotThrow(() -> new WritableBookContent(Collections.nCopies(100, page)));
        assertThrows(IllegalArgumentException.class,
                () -> new WritableBookContent(List.of(new FilteredText<>("x".repeat(1025), null))));
        assertThrows(IllegalArgumentException.class,
                () -> new WritableBookContent(List.of(new FilteredText<>("x", "x".repeat(1025)))));
        assertDoesNotThrow(() -> new WritableBookContent(List.of(new FilteredText<>("x".repeat(1024), null))));
    }
}
