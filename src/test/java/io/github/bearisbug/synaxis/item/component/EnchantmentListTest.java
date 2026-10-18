package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.component.DataComponent;
import io.github.bearisbug.synaxis.component.DataComponents;
import net.kyori.adventure.nbt.BinaryTag;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryTranscoder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EnchantmentListTest extends AbstractItemComponentRegistriesTest<EnchantmentList> {
    private static final RegistryKey<BinaryTag> SHARPNESS = RegistryKey.unsafeOf("minecraft:sharpness");
    private static final RegistryKey<BinaryTag> PUNCH = RegistryKey.unsafeOf("minecraft:punch");

    // This is not a test, but it creates a compile error if the component type is changed away from Unit,
    // as a reminder that tests should be added for that new component type.
    private static final List<DataComponent<EnchantmentList>> SHARED_COMPONENTS = List.of(
            DataComponents.ENCHANTMENTS,
            DataComponents.STORED_ENCHANTMENTS
    );

    @Override
    protected DataComponent<EnchantmentList> component() {
        return SHARED_COMPONENTS.getFirst();
    }

    @Override
    protected List<Map.Entry<String, EnchantmentList>> directReadWriteEntries() {
        return List.of(
                Map.entry("empty", EnchantmentList.EMPTY),
                Map.entry("single entry", new EnchantmentList(Map.of(SHARPNESS, 1))),
                Map.entry("multi entry", new EnchantmentList(Map.of(SHARPNESS, 1, PUNCH, 2)))
        );
    }

    @Test
    void testShorthandNbtSyntax(Registries registries) throws Exception {
        var tag = EngineAdventure.tagStringIO().asTag("""
                {
                    "sharpness": 1,
                    "punch": 2,
                }
                """);
        var coder = new RegistryTranscoder<>(Transcoder.NBT, registries);
        var value = assertOk(component().decode(coder, tag));
        assertEquals(new EnchantmentList(Map.of(SHARPNESS, 1, PUNCH, 2)), value);
    }

    // 26.2 refuses a level outside 1..255 when it decodes the component and drops the connection
    @Test
    void aLevelOutsideOneTo255IsRefused() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new EnchantmentList(SHARPNESS, 256));
        assertTrue(error.getMessage().contains("minecraft:sharpness"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> EnchantmentList.EMPTY.with(SHARPNESS, 0));
        assertEquals(255, new EnchantmentList(SHARPNESS, 255).level(SHARPNESS));
    }

    @Test
    void aStoredLevelOutsideOneTo255IsAnError(Registries registries) throws Exception {
        var tag = EngineAdventure.tagStringIO().asTag("{sharpness: 300}");
        var coder = new RegistryTranscoder<>(Transcoder.NBT, registries);
        assertInstanceOf(Result.Error.class, component().decode(coder, tag));
    }
}
