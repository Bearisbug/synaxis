package io.github.bearisbug.synaxis.item;

import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.item.component.EnchantmentList;
import io.github.bearisbug.synaxis.item.component.ItemBlockState;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.util.RGBLike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hash a window click carries for the cursor item ({@code ItemStack.Hash}) encodes each component with the CRC32
 * transcoder, which reads nothing back. Map components (enchantments, block state, debug stick state, map
 * decorations) are encoded by the map codec, so they hash too, the way a vanilla client hashes them.
 */
@RegistriesTest
public class ItemStackHashRegistriesTest {

    private static final Transcoder<Integer> HASH = Transcoder.CRC32_HASH;

    @Test
    public void anEnchantedItemHashesItsEnchantments(Registries registries) {
        final ItemStack sword = ItemStack.builder(Material.DIAMOND_SWORD)
                .set(DataComponents.ENCHANTMENTS, new EnchantmentList(RegistryKey.unsafeOf("minecraft:sharpness"), 5))
                .build();

        final ItemStackHashImpl.Item hash = assertInstanceOf(ItemStackHashImpl.Item.class, ItemStack.Hash.of(sword, registries));

        assertEquals(HASH.createMap().put("minecraft:sharpness", HASH.createInt(5)).build(),
                hash.addedComponents().get(DataComponents.ENCHANTMENTS));
    }

    @Test
    public void anItemWithABlockStateHashesIt(Registries registries) {
        final ItemStack lantern = ItemStack.builder(Material.LANTERN)
                .set(DataComponents.BLOCK_STATE, new ItemBlockState("hanging", "true"))
                .build();

        final ItemStackHashImpl.Item hash = assertInstanceOf(ItemStackHashImpl.Item.class, ItemStack.Hash.of(lantern, registries));

        assertEquals(HASH.createMap().put("hanging", HASH.createString("true")).build(),
                hash.addedComponents().get(DataComponents.BLOCK_STATE));
    }

    /**
     * A component value its codec cannot encode, which only a plugin can set, fails the hash; the exception names the
     * component, not only the codec's error.
     */
    @Test
    public void aComponentThatDoesNotEncodeIsNamed(Registries registries) {
        record OutOfRange(int red, int green, int blue) implements RGBLike {
        }
        final ItemStack helmet = ItemStack.builder(Material.LEATHER_HELMET)
                .set(DataComponents.DYED_COLOR, new OutOfRange(300, 0, 0))
                .build();

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ItemStack.Hash.of(helmet, registries));

        assertTrue(thrown.getMessage().startsWith("hash of component minecraft:dyed_color: "), thrown.getMessage());
    }
}
