package io.github.bearisbug.synaxis.item.component;

import net.kyori.adventure.nbt.StringBinaryTag;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.color.Color;
import io.github.bearisbug.synaxis.component.DataComponent;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.potion.CustomPotionEffect;
import io.github.bearisbug.synaxis.potion.PotionEffect;
import io.github.bearisbug.synaxis.potion.PotionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertOk;
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
