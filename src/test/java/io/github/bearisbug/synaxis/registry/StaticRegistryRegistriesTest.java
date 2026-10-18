package io.github.bearisbug.synaxis.registry;

import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registries of the bundled vanilla data: an id outside them is no entry, the way {@link Registry} declares it and
 * dynamic registries answer, and their tags are as fixed as their entries.
 */
@RegistriesTest
public class StaticRegistryRegistriesTest {

    @Test
    public void anIdOutsideTheRegistryIsNoEntry() {
        assertNull(Material.fromId(-1));
        assertNull(Material.fromId(Material.staticRegistry().size()));
        assertNull(Material.fromId(Integer.MAX_VALUE));
        assertNull(Block.staticRegistry().getKey(-1));
        assertNull(Block.staticRegistry().getPack(Integer.MAX_VALUE));
        assertEquals(Material.STONE, Material.fromId(Material.STONE.id()));
        assertEquals(-1, Material.staticRegistry().getId(RegistryKey.unsafeOf("synaxis:absent")));
    }

    /** A client sends registry ids, so a bad one must be reported for what it is instead of an index out of bounds. */
    @Test
    public void anUnknownIdReadFromTheNetworkNamesTheRegistry(Registries registries) {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(registries);
        buffer.write(NetworkBuffer.VAR_INT, Material.staticRegistry().size() + 10);

        final String message = assertThrows(IllegalStateException.class,
                () -> buffer.read(RegistryKey.networkType(Registries::material))).getMessage();
        assertTrue(message.contains("Unknown id") && message.contains("minecraft:item"), message);
    }

    /** The tags of a static registry come with its data and are shared by the whole JVM. */
    @Test
    public void staticTagsCannotBeCreatedOrRemoved() {
        final Registry<Material> registry = Material.staticRegistry();
        final TagKey<Material> swords = TagKey.ofHash("#minecraft:swords");
        final TagKey<Material> absent = TagKey.unsafeOf("synaxis:absent");

        assertSame(registry.getTag(swords), registry.getOrCreateTag(swords));
        assertThrows(IllegalStateException.class, () -> registry.getOrCreateTag(absent));
        assertThrows(UnsupportedOperationException.class, () -> registry.removeTag(absent));
        assertNull(registry.getTag(absent));
        assertNotNull(registry.getTag(swords));
    }
}
