package io.github.bearisbug.synaxis.codec;

import com.google.gson.JsonParser;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertError;
import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class StructCodecTest {

    private static final String INT_TYPE = BinaryTagTypes.INT.toString();
    private static final String STRING_TYPE = BinaryTagTypes.STRING.toString();
    private static final String LIST_TYPE = BinaryTagTypes.LIST.toString();

    @Test
    void emptyObject() {
        record Empty() {
        }

        var codec = StructCodec.struct(Empty::new);
        var result = codec.decode(TranscoderNbtImpl.INSTANCE, snbt("{}"));
        assertEquals(new Empty(), assertOk(result));
    }

    @Test
    void singleField() {
        record TheObject(String name) {
        }

        var codec = StructCodec.struct(
                "name", Codec.STRING, TheObject::name,
                TheObject::new);
        var result = codec.decode(TranscoderNbtImpl.INSTANCE, snbt("{name: \"test\"}"));
        assertEquals(new TheObject("test"), assertOk(result));
    }

    @Test
    void singleFieldMissing() {
        record TheObject(String name) {
        }

        var codec = StructCodec.struct(
                "name", Codec.STRING, TheObject::name,
                TheObject::new);
        var result = codec.decode(TranscoderNbtImpl.INSTANCE, snbt("{}"));
        assertError("name: No such key: name", result);
    }

    @Test
    void singleFieldOptionalMissing() {
        record TheObject(@Nullable String name) {
        }

        var codec = StructCodec.struct(
                "name", Codec.STRING.optional(), TheObject::name,
                TheObject::new);
        var result = codec.decode(TranscoderNbtImpl.INSTANCE, snbt("{}"));
        assertEquals(new TheObject(null), assertOk(result));
    }

    @Test
    void singleFieldOptionalMissingDefault() {
        record TheObject(String name) {
        }

        var codec = StructCodec.struct(
                "name", Codec.STRING.optional("defaultValue"), TheObject::name,
                TheObject::new);
        var result = codec.decode(TranscoderNbtImpl.INSTANCE, snbt("{}"));
        assertEquals(new TheObject("defaultValue"), assertOk(result));
    }

    @Test
    void singleFieldOptionalIncorrectTypeButNotMissing() {
        record TheObject(String name) {
        }

        var codec = StructCodec.struct(
                "name", Codec.STRING.optional(), TheObject::name,
                TheObject::new
        );
        var result = codec.decode(TranscoderNbtImpl.INSTANCE, snbt("{\"name\": 2}"));
        assertError("name: Expected string NBT, found " + INT_TYPE + " 2", result);
    }

    /** A mismatched tag can come from a client, so the error may only carry a bounded preview of it. */
    @Test
    void incorrectTypeDiagnosticIsTruncated() {
        record TheObject(int value) {
        }

        var codec = StructCodec.struct(
                "value", Codec.INT, TheObject::value,
                TheObject::new
        );
        var result = codec.decode(TranscoderNbtImpl.INSTANCE, snbt("{value: \"" + "a".repeat(100) + "\"}"));
        assertError("value: Expected int NBT, found " + STRING_TYPE + " \"" + "a".repeat(64) + "\"...", result);

        result = codec.decode(TranscoderNbtImpl.INSTANCE, snbt("{value: [1, 2, 3]}"));
        assertError("value: Expected int NBT, found " + LIST_TYPE + " [3 entries]", result);
    }

    @Test
    void singleFieldOptionalExplicitJsonNull() {
        record TheObject(@Nullable String name) {
        }

        var codec = StructCodec.struct(
                "name", Codec.STRING.optional(), TheObject::name,
                TheObject::new);
        var json = JsonParser.parseString("{\"name\": null}");
        assertEquals(new TheObject(null), assertOk(codec.decode(Transcoder.JSON, json)));
    }

    @Test
    void singleFieldOptionalExplicitJsonNullWithDefault() {
        record TheObject(String name) {
        }

        var codec = StructCodec.struct(
                "name", Codec.STRING.optional("defaultValue"), TheObject::name,
                TheObject::new);
        var json = JsonParser.parseString("{\"name\": null}");
        assertEquals(new TheObject("defaultValue"), assertOk(codec.decode(Transcoder.JSON, json)));
    }

    @Test
    void inlineField() {
        record InnerObject(String value) {
        }
        record TheObject(String name, InnerObject inner) {
        }

        var codec = StructCodec.struct(
                "name", Codec.STRING, TheObject::name,
                StructCodec.INLINE, StructCodec.struct(
                        "value", Codec.STRING, InnerObject::value,
                        InnerObject::new
                ), TheObject::inner,
                TheObject::new);
        var result = codec.decode(TranscoderNbtImpl.INSTANCE, snbt("{name: \"test\", value: \"innerValue\"}"));
        assertEquals(new TheObject("test", new InnerObject("innerValue")), assertOk(result));

        var encodeResult = codec.encode(TranscoderNbtImpl.INSTANCE, new TheObject("test", new InnerObject("innerValue")));
        assertEquals(snbt("{name: \"test\", value: \"innerValue\"}"), assertOk(encodeResult));
    }

    @Test
    void inlineFieldEmpty() {
        record InnerObject(String value) {
        }
        record TheObject(String name, InnerObject inner) {
        }

        var codec = StructCodec.struct(
                "name", Codec.STRING, TheObject::name,
                StructCodec.INLINE, StructCodec.struct(
                        "value", Codec.STRING, InnerObject::value,
                        InnerObject::new
                ), TheObject::inner,
                TheObject::new);
        var result = codec.decode(TranscoderNbtImpl.INSTANCE, snbt("{name: \"test\", value: \"innerValue\"}"));
        assertEquals(new TheObject("test", new InnerObject("innerValue")), assertOk(result));

        var encodeResult = codec.encode(TranscoderNbtImpl.INSTANCE, new TheObject("test", new InnerObject("innerValue")));
        assertEquals(snbt("{name: \"test\", value: \"innerValue\"}"), assertOk(encodeResult));
    }

    private static BinaryTag snbt(String snbt) {
        return assertDoesNotThrow(() -> EngineAdventure.tagStringIO().asTag(snbt));
    }


}
