package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.component.DataComponent;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.Registries;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class RecipesTest extends AbstractItemComponentRegistriesTest<List<String>> {

    @Override
    protected DataComponent<List<String>> component() {
        return DataComponents.RECIPES;
    }

    @Override
    protected List<Map.Entry<String, List<String>>> directReadWriteEntries() {
        return List.of(
                entry("empty", List.of()),
                entry("two recipes", List.of("minecraft:stick", "minecraft:oak_planks"))
        );
    }

    /**
     * 26.2 gives {@code minecraft:recipes} no stream codec of its own, so the client reads it the way
     * {@code DataComponentType.Builder#build} falls back to: {@code ByteBufCodecs.fromCodecWithRegistries}
     * over {@code Recipe.KEY_CODEC.listOf()}, one nameless NBT tag holding a list of string tags.
     */
    @Test
    void theRecipesAreSentAsAnNbtListOfStrings(Registries registries) throws IOException {
        assertArrayEquals(nbtStringList("minecraft:stick", "minecraft:oak_planks"),
                NetworkBuffer.makeArray(buffer -> component().write(buffer,
                        List.of("minecraft:stick", "minecraft:oak_planks")), registries));
        assertArrayEquals(nbtStringList(),
                NetworkBuffer.makeArray(buffer -> component().write(buffer, List.of()), registries));
    }

    /** The bytes 26.2's {@code NbtIo#writeAnyTag} writes for a list tag of strings. */
    private static byte[] nbtStringList(String... ids) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(9); // the tag type, TAG_List
        out.writeByte(ids.length == 0 ? 0 : 8); // the element type, TAG_String, or TAG_End for no elements
        out.writeInt(ids.length);
        for (String id : ids) out.writeUTF(id);
        return bytes.toByteArray();
    }
}
