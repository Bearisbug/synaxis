package io.github.bearisbug.synaxis.item;

import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.item.component.SeededContainerLoot;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryTranscoder;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A handful of data components are registered without a network type or without a codec. DataComponent#isSynced and
 * DataComponent#isSerialized exist to filter them, so neither serialization path may blow up on them.
 */
@RegistriesTest
public class UnsyncedComponentRegistriesTest {

    @Test
    public void componentWithoutNetworkTypeIsNotSent(Registries registries) {
        var itemStack = ItemStack.of(Material.CHEST)
                .with(DataComponents.CONTAINER_LOOT, new SeededContainerLoot("minecraft:chests/simple_dungeon", 0));

        final byte[] written = assertDoesNotThrow(() ->
                NetworkBuffer.makeArray(buffer -> buffer.write(ItemStack.NETWORK_TYPE, itemStack), registries));
        final ItemStack back = NetworkBuffer.wrap(written, 0, written.length, registries).read(ItemStack.NETWORK_TYPE);

        assertEquals(Material.CHEST, back.material());
        assertNull(back.get(DataComponents.CONTAINER_LOOT));
    }

    @Test
    public void componentWithoutCodecIsNotSerialized(Registries registries) {
        var itemStack = ItemStack.of(Material.STONE).with(DataComponents.ADDITIONAL_TRADE_COST, 3);

        var coder = new RegistryTranscoder<>(Transcoder.NBT, registries);
        var encoded = assertOk(ItemStack.CODEC.encode(coder, itemStack));
        var back = assertOk(ItemStack.CODEC.decode(coder, encoded));

        assertEquals(Material.STONE, back.material());
        assertNull(back.get(DataComponents.ADDITIONAL_TRADE_COST));
    }

    @Test
    public void itemHashSkipsComponentsWithoutCodec(Registries registries) {
        var itemStack = ItemStack.of(Material.STONE).with(DataComponents.ADDITIONAL_TRADE_COST, 3);
        assertDoesNotThrow(() -> ItemStack.Hash.of(itemStack, registries));
    }
}
