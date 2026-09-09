package net.minestom.server.item.component;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.Result;
import net.minestom.server.codec.Transcoder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class VanillaVariantNameTest {

    private static <T> String encode(Codec<T> codec, T value) {
        Result<BinaryTag> result = codec.encode(Transcoder.NBT, value);
        BinaryTag tag = (BinaryTag) assertInstanceOf(Result.Ok.class, result).value();
        return assertInstanceOf(StringBinaryTag.class, tag).value();
    }

    @Test
    public void rabbitVariantsUseVanillaNames() {
        assertEquals("brown", encode(RabbitVariant.CODEC, RabbitVariant.BROWN));
        assertEquals("white_splotched", encode(RabbitVariant.CODEC, RabbitVariant.BLACK_AND_WHITE));
        assertEquals("salt", encode(RabbitVariant.CODEC, RabbitVariant.SALT_AND_PEPPER));
        assertEquals("evil", encode(RabbitVariant.CODEC, RabbitVariant.KILLER_BUNNY));
    }

    @Test
    public void parrotColorsUseVanillaNames() {
        assertEquals("red_blue", encode(ParrotVariant.CODEC, ParrotVariant.RED_BLUE));
        assertEquals("gray", encode(ParrotVariant.CODEC, ParrotVariant.GREY));
    }
}
