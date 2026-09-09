package dev.gameengine.item.component;

import net.kyori.adventure.nbt.StringBinaryTag;
import dev.gameengine.codec.Transcoder;
import dev.gameengine.color.Color;
import dev.gameengine.component.DataComponent;
import dev.gameengine.component.DataComponents;
import dev.gameengine.potion.CustomPotionEffect;
import dev.gameengine.potion.PotionEffect;
import dev.gameengine.potion.PotionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.gameengine.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PotionContentsTest extends AbstractItemComponentRegistriesTest<PotionContents> {

    @Override
    protected DataComponent<PotionContents> component() {
        return DataComponents.POTION_CONTENTS;
    }

    @Override
    protected List<Map.Entry<String, PotionContents>> directReadWriteEntries() {
        return List.of(
                Map.entry("empty", PotionContents.EMPTY),
                Map.entry("single effect", new PotionContents(PotionType.STRONG_SWIFTNESS)),
                Map.entry("single effect, color", new PotionContents(PotionType.STRONG_SWIFTNESS, new Color(0x123456))),
                Map.entry("custom effect", new PotionContents(new CustomPotionEffect(PotionEffect.INVISIBILITY, (byte) 2, 10, true, false, true))),
                Map.entry("custom effect recursive", new PotionContents(new CustomPotionEffect(PotionEffect.INVISIBILITY, new CustomPotionEffect.Settings(
                        (byte) 2, 10, true, false, true, new CustomPotionEffect.Settings(
                        (byte) 2, 10, true, false, true, null))))),
                Map.entry("custom effect", new PotionContents(List.of(
                        new CustomPotionEffect(PotionEffect.INVISIBILITY, (byte) 2, 10, true, false, true),
                        new CustomPotionEffect(PotionEffect.STRENGTH, (byte) 3, 10000, false, true, false)
                )))
        );
    }

    @Test
    void alternativeNbtSyntax() {
        var value = assertOk(DataComponents.POTION_CONTENTS.decode(Transcoder.NBT,
                StringBinaryTag.stringBinaryTag("minecraft:strong_swiftness")));
        var expected = new PotionContents(PotionType.STRONG_SWIFTNESS, null, List.of(), null);
        assertEquals(expected, value);
    }
}
