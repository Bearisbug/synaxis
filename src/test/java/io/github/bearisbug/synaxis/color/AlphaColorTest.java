package io.github.bearisbug.synaxis.color;

import com.google.gson.JsonPrimitive;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AlphaColorTest {

    @Test
    public void alphaColorTest() {
        AlphaColor color = new AlphaColor(0x11, 0x22, 0x33, 0x44);
        assertEquals(0x11223344, color.asARGB());
        assertEquals(0x22334411, color.asRGBA());

        String hexString = "#AABBCCDD";
        assertEquals(
                new AlphaColor(0xDDAABBCC),
                AlphaColor.fromRGBAHexString(hexString)
        );
        assertEquals(
                new AlphaColor(0xAABBCCDD),
                AlphaColor.fromARGBHexString(hexString)
        );
    }

    @Test
    public void codecTest() {
        AlphaColor testColor = new AlphaColor(0x01, 0x23, 0x45, 0x67);
        BinaryTag elementARGB = AlphaColor.ARGB_STRING_CODEC.encode(Transcoder.NBT, testColor).orElseThrow();
        BinaryTag elementRGBA = AlphaColor.RGBA_STRING_CODEC.encode(Transcoder.NBT, testColor).orElseThrow();
        assertEquals(StringBinaryTag.stringBinaryTag("#01234567"), elementARGB);
        assertEquals(StringBinaryTag.stringBinaryTag("#23456701"), elementRGBA);
    }

    @Test
    @SuppressWarnings("deprecation") // STRING_CODEC is checked along with its replacements
    public void anInvalidHexStringIsAnErrorNamingIt() {
        final var argb = assertInstanceOf(Result.Error.class,
                AlphaColor.ARGB_STRING_CODEC.decode(Transcoder.JSON, new JsonPrimitive("#12345")));
        final var rgba = assertInstanceOf(Result.Error.class,
                AlphaColor.RGBA_STRING_CODEC.decode(Transcoder.JSON, new JsonPrimitive("#12345")));
        final var shadow = assertInstanceOf(Result.Error.class,
                AlphaColor.STRING_CODEC.decode(Transcoder.JSON, new JsonPrimitive("#12345")));

        assertTrue(argb.message().contains("invalid hex color '#12345', expected #AARRGGBB"), argb.message());
        assertTrue(rgba.message().contains("invalid hex color '#12345', expected #RRGGBBAA"), rgba.message());
        assertTrue(shadow.message().contains("invalid hex color '#12345', expected #RRGGBBAA"), shadow.message());
    }

    @Test
    @SuppressWarnings("deprecation") // STRING_CODEC is checked along with its replacements
    public void aValidHexStringDecodesInTheOrderOfItsCodec() {
        final JsonPrimitive hex = new JsonPrimitive("#23456701");

        assertEquals(0x01234567, AlphaColor.fromARGBLike(
                AlphaColor.RGBA_STRING_CODEC.decode(Transcoder.JSON, hex).orElseThrow()).asARGB());
        assertEquals(0x01234567, AlphaColor.fromARGBLike(
                AlphaColor.STRING_CODEC.decode(Transcoder.JSON, hex).orElseThrow()).asARGB());
        assertEquals(0x23456701, AlphaColor.fromARGBLike(
                AlphaColor.ARGB_STRING_CODEC.decode(Transcoder.JSON, hex).orElseThrow()).asARGB());
    }
}
