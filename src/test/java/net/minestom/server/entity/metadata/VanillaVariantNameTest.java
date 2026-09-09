package net.minestom.server.entity.metadata;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.Result;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.entity.metadata.animal.RabbitMeta;
import net.minestom.server.entity.metadata.animal.tameable.ParrotMeta;
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
        assertEquals("brown", encode(RabbitMeta.Variant.CODEC, RabbitMeta.Variant.BROWN));
        assertEquals("white_splotched", encode(RabbitMeta.Variant.CODEC, RabbitMeta.Variant.BLACK_AND_WHITE));
        assertEquals("salt", encode(RabbitMeta.Variant.CODEC, RabbitMeta.Variant.SALT_AND_PEPPER));
        assertEquals("evil", encode(RabbitMeta.Variant.CODEC, RabbitMeta.Variant.KILLER_BUNNY));
    }

    @Test
    public void parrotColorsUseVanillaNames() {
        assertEquals("red_blue", encode(ParrotMeta.Color.CODEC, ParrotMeta.Color.RED_BLUE));
        assertEquals("gray", encode(ParrotMeta.Color.CODEC, ParrotMeta.Color.GREY));
    }
}
