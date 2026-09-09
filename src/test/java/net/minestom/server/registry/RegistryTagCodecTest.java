package net.minestom.server.registry;

import net.kyori.adventure.key.Key;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.item.Material;
import net.minestom.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static net.minestom.server.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RegistryTagImpl.Direct compares its entries in order so that a tag built from the registry equals the same tag
 * read back from the network, which preserves the declared order. The codec has to preserve it too.
 */
@RegistriesTest
public class RegistryTagCodecTest {

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
}
