package io.github.bearisbug.synaxis.item;

import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryTranscoder;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import net.kyori.adventure.nbt.BinaryTag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static final List<ItemStack> CONTAINER_CONTENTS =
            List.of(ItemStack.of(Material.STONE), ItemStack.AIR, ItemStack.of(Material.DIRT, 2));

    /**
     * 26.2 writes every slot of a container as an optional item stack template, so an empty slot is a single false.
     */
    @Test
    public void containerWritesEachSlotAsAnOptionalTemplate(Registries registries) {
        final byte[] expected = NetworkBuffer.makeArray(buffer -> {
            buffer.write(NetworkBuffer.VAR_INT, 3); // slots
            buffer.write(NetworkBuffer.BOOLEAN, true);
            buffer.write(NetworkBuffer.VAR_INT, Material.STONE.id());
            buffer.write(NetworkBuffer.VAR_INT, 1); // amount
            buffer.write(NetworkBuffer.VAR_INT, 0); // added components
            buffer.write(NetworkBuffer.VAR_INT, 0); // removed components
            buffer.write(NetworkBuffer.BOOLEAN, false);
            buffer.write(NetworkBuffer.BOOLEAN, true);
            buffer.write(NetworkBuffer.VAR_INT, Material.DIRT.id());
            buffer.write(NetworkBuffer.VAR_INT, 2);
            buffer.write(NetworkBuffer.VAR_INT, 0);
            buffer.write(NetworkBuffer.VAR_INT, 0);
        });

        final byte[] written = NetworkBuffer.makeArray(
                buffer -> DataComponents.CONTAINER.write(buffer, CONTAINER_CONTENTS), registries);

        assertArrayEquals(expected, written);
        assertEquals(CONTAINER_CONTENTS,
                DataComponents.CONTAINER.read(NetworkBuffer.wrap(written, 0, written.length, registries)));
    }

    /**
     * 26.2 stores a container as its filled slots, each with its index.
     */
    @Test
    public void containerCodecWritesTheFilledSlots(Registries registries) throws IOException {
        final var coder = new RegistryTranscoder<>(Transcoder.NBT, registries);
        final BinaryTag expected = EngineAdventure.tagStringIO().asTag(
                "[{slot:0,item:{id:\"minecraft:stone\",count:1}},{slot:2,item:{id:\"minecraft:dirt\",count:2}}]");

        final BinaryTag encoded = assertOk(DataComponents.CONTAINER.encode(coder, CONTAINER_CONTENTS));

        assertEquals(expected, encoded);
        assertEquals(CONTAINER_CONTENTS, assertOk(DataComponents.CONTAINER.decode(coder, encoded)));
        assertInstanceOf(Result.Error.class, DataComponents.CONTAINER.decode(coder,
                EngineAdventure.tagStringIO().asTag("[{slot:256,item:{id:\"minecraft:stone\",count:1}}]")));
    }

    /**
     * Items saved before the container followed 26.2, such as those in a stored {@code synaxis:player}, hold the
     * container as a plain item list.
     */
    @Test
    public void containerCodecStillReadsAPlainItemList(Registries registries) {
        final var stored = JsonParser.parseString(
                "[{\"id\":\"minecraft:stone\",\"count\":1},{\"id\":\"minecraft:air\",\"count\":0},{\"id\":\"minecraft:dirt\",\"count\":2}]");

        assertEquals(CONTAINER_CONTENTS, assertOk(DataComponents.CONTAINER.decode(
                new RegistryTranscoder<>(Transcoder.JSON, registries), stored)));
    }

    /**
     * These components hold item stack templates on the wire, which a 26.2 client cannot build from air: it drops the
     * connection while decoding the item. Air is refused where it is set instead of when the item reaches a client.
     */
    @Test
    public void templateComponentsRejectAir() {
        var remainder = assertThrows(IllegalArgumentException.class,
                () -> ItemStack.of(Material.MUSHROOM_STEW).with(DataComponents.USE_REMAINDER, ItemStack.AIR));
        assertEquals("minecraft:use_remainder cannot hold air; remove the component with without() instead",
                remainder.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> ItemStack.of(Material.STONE).with(DataComponents.SULFUR_CUBE_CONTENT, ItemStack.AIR));

        var bundle = assertThrows(IllegalArgumentException.class, () -> ItemStack.of(Material.BUNDLE)
                .with(DataComponents.BUNDLE_CONTENTS, List.of(ItemStack.of(Material.STONE), ItemStack.AIR)));
        assertEquals("minecraft:bundle_contents cannot hold air (index 1); leave the empty slots out instead",
                bundle.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ItemStack.of(Material.CROSSBOW)
                .with(DataComponents.CHARGED_PROJECTILES, List.of(ItemStack.AIR)));
    }
}
