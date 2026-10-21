package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.component.DataComponent;
import io.github.bearisbug.synaxis.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static net.kyori.adventure.nbt.StringBinaryTag.stringBinaryTag;
import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ItemRarityTest extends AbstractItemComponentRegistriesTest<ItemRarity> {
    @Override
    protected DataComponent<ItemRarity> component() {
        return DataComponents.RARITY;
    }

    @Override
    protected List<Map.Entry<String, ItemRarity>> directReadWriteEntries() {
        return List.of(
                Map.entry("common", ItemRarity.COMMON)
        );
    }

    @Test
    void testReadFromNbtInt() {
        var value = assertOk(ItemRarity.CODEC.decode(Transcoder.NBT, stringBinaryTag("rare")));
        assertEquals(ItemRarity.RARE, value);
    }

    /**
     * An unknown rarity is an error that names it, not a value of null; the name is read whatever its case.
     */
    @Test
    void anUnknownRarityIsAnErrorNamingIt() {
        final var error = assertInstanceOf(Result.Error.class,
                ItemRarity.CODEC.decode(Transcoder.NBT, stringBinaryTag("legendary")));
        assertTrue(error.message().contains("LEGENDARY"), error.message());
        assertEquals(ItemRarity.RARE, assertOk(ItemRarity.CODEC.decode(Transcoder.NBT, stringBinaryTag("RARE"))));
    }
}
