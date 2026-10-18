package io.github.bearisbug.synaxis.registry;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.StringBinaryTag;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RegistryTagImpl.Direct compares its entries in order so that a tag built from the registry equals the same tag
 * read back from the network, which preserves the declared order. The codec has to preserve it too.
 */
@RegistriesTest
public class RegistryTagCodecRegistriesTest {

    @Test
    public void directTagCodecPreservesEntryOrder(Registries registries) {
        final List<RegistryKey<Material>> keys = List.of(
                Material.DIAMOND.registryKey(), Material.STONE.registryKey(), Material.STICK.registryKey(),
                Material.APPLE.registryKey(), Material.BUCKET.registryKey(), Material.TORCH.registryKey(),
                Material.OAK_LOG.registryKey(), Material.IRON_INGOT.registryKey());
        final RegistryTag<Material> tag = RegistryTag.direct(keys);

        final var coder = new RegistryTranscoder<>(Transcoder.NBT, registries);
        final var codec = RegistryTag.<Material>codec(Registries::material);
        final var decoded = assertOk(codec.decode(coder, assertOk(codec.encode(coder, tag))));

        final List<Key> decodedKeys = new ArrayList<>();
        decoded.forEach(key -> decodedKeys.add(key.key()));
        assertEquals(keys.stream().map(RegistryKey::key).toList(), decodedKeys);
        assertEquals(tag, decoded);
    }

    /**
     * A tag of one entry is written as that entry alone. With registries to check against, that entry has to be in the
     * registry like every entry of a longer list, or it is only found out when the tag goes to a client.
     */
    @Test
    public void singleUnknownEntryIsAnError(Registries registries) {
        final var coder = new RegistryTranscoder<>(Transcoder.NBT, registries);
        final var codec = RegistryTag.<Block>codec(Registries::blocks);

        final String message = assertInstanceOf(Result.Error.class,
                codec.decode(coder, StringBinaryTag.stringBinaryTag("minecraft:not_a_block"))).message();
        assertTrue(message.contains("minecraft:not_a_block"), message);
    }

    @Test
    public void singleKnownEntryRoundTrips(Registries registries) {
        final var coder = new RegistryTranscoder<>(Transcoder.NBT, registries);
        final var codec = RegistryTag.<Block>codec(Registries::blocks);
        final RegistryTag<Block> stone = RegistryTag.direct(Block.STONE.registryKey());

        final var encoded = assertOk(codec.encode(coder, stone));
        assertEquals(StringBinaryTag.stringBinaryTag("minecraft:stone"), encoded);
        assertEquals(stone, assertOk(codec.decode(coder, encoded)));
    }
}
