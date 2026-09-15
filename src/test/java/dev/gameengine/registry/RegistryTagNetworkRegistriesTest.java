package dev.gameengine.registry;

import dev.gameengine.item.Material;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An inline registry tag declares its entry count before sending any of them, so the count has to be
 * checked against the registry and against the remaining input before anything is reserved.
 */
@RegistriesTest
public class RegistryTagNetworkRegistriesTest {

    private static final NetworkBuffer.Type<RegistryTag<Material>> TAG_TYPE =
            RegistryTag.networkType(Registries::material);

    private static NetworkBuffer declaring(int encodedCount, Registries registries) {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(16, registries);
        buffer.write(NetworkBuffer.VAR_INT, encodedCount);
        return buffer;
    }

    @Test
    public void inlineTagLongerThanTheRegistryIsRejected(Registries registries) {
        final int size = registries.material().size();
        assertThrows(RuntimeException.class,
                () -> declaring(size + 2, registries).read(TAG_TYPE));
        assertThrows(RuntimeException.class,
                () -> declaring(Integer.MAX_VALUE, registries).read(TAG_TYPE));
    }

    @Test
    public void inlineTagShorterThanTheInputRunsOutOfInput(Registries registries) {
        // Within the registry size, so the entries themselves must be the thing that is missing
        assertThrows(IndexOutOfBoundsException.class,
                () -> declaring(registries.material().size(), registries).read(TAG_TYPE));
    }

    @Test
    public void emptyAndSingleEntryTagsStillRoundTrip(Registries registries) {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(16, registries);
        buffer.write(TAG_TYPE, RegistryTag.empty());
        assertEquals(RegistryTag.empty(), buffer.read(TAG_TYPE));

        final RegistryTag<Material> direct = RegistryTag.direct(Material.STONE.registryKey());
        buffer.write(TAG_TYPE, direct);
        assertEquals(direct, buffer.read(TAG_TYPE));
    }
}
