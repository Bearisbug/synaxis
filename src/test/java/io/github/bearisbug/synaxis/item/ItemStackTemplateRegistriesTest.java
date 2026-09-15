package io.github.bearisbug.synaxis.item;

import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryTranscoder;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@RegistriesTest
public class ItemStackTemplateRegistriesTest {

    /**
     * Nested items are written with {@link io.github.bearisbug.synaxis.component.DataComponent#PATCH_NETWORK_TYPE}, so they
     * have to hand over their patch. Handing over the resolved component map drops every removal marker and inflates
     * the packet with the whole material prototype.
     */
    @Test
    public void nestedItemKeepsComponentRemovalOverNetwork(Registries registries) {
        var beef = ItemStack.of(Material.COOKED_BEEF).without(DataComponents.CONSUMABLE);
        var bundle = ItemStack.of(Material.BUNDLE).with(DataComponents.BUNDLE_CONTENTS, List.of(beef));

        final byte[] written = NetworkBuffer.makeArray(buffer -> buffer.write(ItemStack.NETWORK_TYPE, bundle), registries);
        final ItemStack back = NetworkBuffer.wrap(written, 0, written.length, registries).read(ItemStack.NETWORK_TYPE);

        var contents = back.get(DataComponents.BUNDLE_CONTENTS);
        assertEquals(1, contents.size());
        assertNull(contents.getFirst().get(DataComponents.CONSUMABLE), "component removal must survive the network");
        assertEquals(bundle, back);
    }

    /**
     * The NBT and the network path have to agree on what a nested item looks like.
     */
    @Test
    public void nestedItemMatchesTheNbtPath(Registries registries) {
        var beef = ItemStack.of(Material.COOKED_BEEF).without(DataComponents.CONSUMABLE);
        var bundle = ItemStack.of(Material.BUNDLE).with(DataComponents.BUNDLE_CONTENTS, List.of(beef));

        var coder = new RegistryTranscoder<>(Transcoder.NBT, registries);
        var encoded = assertOk(ItemStack.CODEC.encode(coder, bundle));
        var fromNbt = assertOk(ItemStack.CODEC.decode(coder, encoded));

        final byte[] written = NetworkBuffer.makeArray(buffer -> buffer.write(ItemStack.NETWORK_TYPE, bundle), registries);
        final ItemStack fromNetwork = NetworkBuffer.wrap(written, 0, written.length, registries).read(ItemStack.NETWORK_TYPE);

        assertEquals(fromNbt, fromNetwork);
    }
}
