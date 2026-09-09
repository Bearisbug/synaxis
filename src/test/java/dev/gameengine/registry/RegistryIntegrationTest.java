package dev.gameengine.registry;

import net.kyori.adventure.key.Key;
import dev.gameengine.gamedata.DataPack;
import dev.gameengine.world.DimensionType;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@EnvTest
public class RegistryIntegrationTest {

    @Test
    void testUnnamedPack(Env env) {
        DynamicRegistry<DimensionType> dimensionRegistry = env.process().dimensionType();
        DimensionType dimensionType = DimensionType.builder()
                .ambientLight(2f)
                .build();
        var registryKey = dimensionRegistry.register(Key.key("toocool:fortests"), dimensionType, DataPack.ENGINE_UNNAMED);
        assertEquals(dimensionType, dimensionRegistry.get(registryKey));
        assertEquals(DataPack.ENGINE_UNNAMED, dimensionRegistry.getPack(registryKey));
        assertDoesNotThrow(() -> {
            dimensionRegistry.registryDataPacket(env.process(), false);
        }, "Registry data packet should not throw for null pack");
    }

    /**
     * Removing an entry shifts the id of every entry after it, so the key to id mapping has to be rebuilt or the
     * registry hands out ids that point at the wrong entry.
     */
    @Test
    void removeRemapsTheIdsOfLaterEntries(Env env) {
        DynamicRegistry<DimensionType> registry = env.process().dimensionType();
        var first = registry.register(Key.key("toocool:one"), DimensionType.builder().ambientLight(1f).build());
        var second = registry.register(Key.key("toocool:two"), DimensionType.builder().ambientLight(2f).build());
        var third = registry.register(Key.key("toocool:three"), DimensionType.builder().ambientLight(3f).build());

        final int firstId = registry.getId(first);
        assertEquals(firstId + 1, registry.getId(second));
        assertEquals(firstId + 2, registry.getId(third));

        assertTrue(registry.remove(second.key()));

        assertNull(registry.get(second));
        assertEquals(firstId, registry.getId(first));
        assertEquals(firstId + 1, registry.getId(third), "ids after the removed entry must be remapped");
        assertEquals(third, registry.getKey(firstId + 1));
    }

    @Test
    void testDifferentPacksInterlaced(Env env) {
        DynamicRegistry<DimensionType> dimensionRegistry = env.process().dimensionType();
        DimensionType dimensionType = DimensionType.builder()
                .ambientLight(2f)
                .build();
        assertDoesNotThrow(()-> dimensionRegistry.register(Key.key("toocool:fortests"), dimensionType, DataPack.ENGINE_UNNAMED));
        assertDoesNotThrow(() -> dimensionRegistry.register(Key.key("toocool:fortests2"), dimensionType, DataPack.MINECRAFT_CORE));
    }
}
