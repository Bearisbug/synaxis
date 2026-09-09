package dev.gameengine.item.component;

import dev.gameengine.codec.Transcoder;
import dev.gameengine.component.DataComponent;
import dev.gameengine.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static net.kyori.adventure.nbt.StringBinaryTag.stringBinaryTag;
import static dev.gameengine.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
