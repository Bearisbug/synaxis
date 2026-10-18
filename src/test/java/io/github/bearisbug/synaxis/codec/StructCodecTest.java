package io.github.bearisbug.synaxis.codec;

import com.google.gson.JsonParser;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Map;

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
        var result = codec.decode(Transcoder.NBT, snbt("{}"));
        assertEquals(new Empty(), assertOk(result));
    }

    @Test
    void singleField() {
        record TheObject(String name) {
        }

        var codec = StructCodec.struct(
                "name", Codec.STRING, TheObject::name,
                TheObject::new);
        var result = codec.decode(Transcoder.NBT, snbt("{name: \"test\"}"));
        assertEquals(new TheObject("test"), assertOk(result));
    }

    @Test
    void singleFieldMissing() {
        record TheObject(String name) {
        }

        var codec = StructCodec.struct(
                "name", Codec.STRING, TheObject::name,
                TheObject::new);
        var result = codec.decode(Transcoder.NBT, snbt("{}"));
        assertError("name: No such key: name", result);
    }

    @Test
    void singleFieldOptionalMissing() {
        record TheObject(@Nullable String name) {
        }

        var codec = StructCodec.struct(
                "name", Codec.STRING.optional(), TheObject::name,
                TheObject::new);
        var result = codec.decode(Transcoder.NBT, snbt("{}"));
        assertEquals(new TheObject(null), assertOk(result));
    }

    @Test
    void singleFieldOptionalMissingDefault() {
        record TheObject(String name) {
        }

        var codec = StructCodec.struct(
                "name", Codec.STRING.optional("defaultValue"), TheObject::name,
                TheObject::new);
        var result = codec.decode(Transcoder.NBT, snbt("{}"));
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
        var result = codec.decode(Transcoder.NBT, snbt("{\"name\": 2}"));
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
        var result = codec.decode(Transcoder.NBT, snbt("{value: \"" + "a".repeat(100) + "\"}"));
        assertError("value: Expected int NBT, found " + STRING_TYPE + " \"" + "a".repeat(64) + "\"...", result);

        result = codec.decode(Transcoder.NBT, snbt("{value: [1, 2, 3]}"));
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
        var result = codec.decode(Transcoder.NBT, snbt("{name: \"test\", value: \"innerValue\"}"));
        assertEquals(new TheObject("test", new InnerObject("innerValue")), assertOk(result));

        var encodeResult = codec.encode(Transcoder.NBT, new TheObject("test", new InnerObject("innerValue")));
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
        var result = codec.decode(Transcoder.NBT, snbt("{name: \"test\", value: \"innerValue\"}"));
        assertEquals(new TheObject("test", new InnerObject("innerValue")), assertOk(result));

        var encodeResult = codec.encode(Transcoder.NBT, new TheObject("test", new InnerObject("innerValue")));
        assertEquals(snbt("{name: \"test\", value: \"innerValue\"}"), assertOk(encodeResult));
    }

    @Test
    void unionTypeKeyDecodedToNull() {
        record Leaf(String name) {
        }

        // A discriminator codec that maps an unregistered key to null, like ConsumeEffectType.CODEC does.
        var keyCodec = Codec.STRING.transform(Map.of("leaf", "leaf")::get, name -> name);
        var codec = keyCodec.unionType(
                type -> type.equals("leaf")
                        ? StructCodec.struct("name", Codec.STRING, Leaf::name, Leaf::new) : null,
                Leaf::name);

        var result = codec.decode(Transcoder.NBT, snbt("{type: \"unknown\", name: \"test\"}"));
        assertError("no union value: null", result);
    }

    /** An optional discriminator falls back to its default only when it is absent, not when it does not decode. */
    @Test
    void unionTypeOptionalKeyDefaultsOnlyWhenAbsent() {
        record Leaf(String name) {
        }

        var codec = Codec.STRING.optional("leaf").unionType(
                type -> type.equals("leaf")
                        ? StructCodec.struct("name", Codec.STRING, Leaf::name, Leaf::new) : null,
                _ -> "leaf");

        assertEquals(new Leaf("test"), assertOk(codec.decode(Transcoder.NBT, snbt("{name: \"test\"}"))));
        assertError("Expected string NBT, found " + INT_TYPE + " 5",
                codec.decode(Transcoder.NBT, snbt("{type: 5, name: \"test\"}")));
    }

    /**
     * Optional only lets an inlined struct write nothing for null. The inlined struct reads its own fields, and a
     * field of it that does not decode fails the whole value instead of turning it into the default.
     */
    @Test
    void inlineOptionalReportsAFieldThatDoesNotDecode() {
        record InnerObject(String value) {
        }
        record TheObject(String name, @Nullable InnerObject inner) {
        }

        var codec = StructCodec.struct(
                "name", Codec.STRING, TheObject::name,
                StructCodec.INLINE, StructCodec.struct(
                        "value", Codec.STRING, InnerObject::value,
                        InnerObject::new
                ).optional(), TheObject::inner,
                TheObject::new);

        assertEquals(new TheObject("test", new InnerObject("innerValue")),
                assertOk(codec.decode(Transcoder.NBT, snbt("{name: \"test\", value: \"innerValue\"}"))));
        assertError(StructCodec.INLINE + ": value: Expected string NBT, found " + INT_TYPE + " 5",
                codec.decode(Transcoder.NBT, snbt("{name: \"test\", value: 5}")));
        assertEquals(snbt("{name: \"test\"}"),
                assertOk(codec.encode(Transcoder.NBT, new TheObject("test", null))));
    }

    private static BinaryTag snbt(String snbt) {
        return assertDoesNotThrow(() -> EngineAdventure.tagStringIO().asTag(snbt));
    }


}
