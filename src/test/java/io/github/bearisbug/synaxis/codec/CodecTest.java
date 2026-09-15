package io.github.bearisbug.synaxis.codec;

import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static net.kyori.adventure.nbt.IntBinaryTag.intBinaryTag;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class CodecTest {

    /**
     * Result.Error requires a non-null message, so building one from a bare getMessage() throws NPE inside the
     * catch block for any exception without a message.
     */
    @Test
    public void transformEncodeFailureWithoutMessageIsAnError() {
        final Codec<String> codec = Codec.STRING.transform(value -> value, _ -> {
            throw new IllegalStateException();
        });
        Assertions.assertInstanceOf(Result.Error.class, codec.encode(Transcoder.NBT, "a"));
    }

    /**
     * Every sibling combinator reports an oversized collection as a Result.Error, so encode must not throw.
     */
    @Test
    public void collectionEncodeOverMaxSizeIsAnError() {
        Assertions.assertInstanceOf(Result.Error.class,
                Codec.STRING.list(1).encode(Transcoder.NBT, List.of("a", "b")));
        Assertions.assertInstanceOf(Result.Error.class,
                Codec.STRING.set(1).encode(Transcoder.NBT, Set.of("a", "b")));
    }

    /**
     * A transcoder map can only be keyed by strings, and dropping the entries silently produced an empty map.
     */
    @Test
    public void mapWithNonStringKeysIsAnError() {
        Assertions.assertInstanceOf(Result.Error.class,
                Codec.INT.mapValue(Codec.STRING).encode(Transcoder.NBT, Map.of(1, "a")));
    }

    /**
     * Codec's own class documentation advertises {@code Codec.STRING.optional().set()}, so an optional nested in a
     * collection has to round trip instead of throwing out of encode and decode.
     */
    @Test
    public void optionalInsideListRoundTrips() {
        final Codec<List<String>> codec = Codec.STRING.optional().list();
        final var encoded = Assertions.assertDoesNotThrow(
                () -> CodecAssertions.assertOk(codec.encode(Transcoder.NBT, Arrays.asList("a", null))));
        assertEquals(List.of("a"), Assertions.assertDoesNotThrow(
                () -> CodecAssertions.assertOk(codec.decode(Transcoder.NBT, encoded))));
    }

    @Test
    public void optionalInsideSetRoundTrips() {
        final Codec<Set<String>> codec = Codec.STRING.optional().set();
        final var encoded = Assertions.assertDoesNotThrow(
                () -> CodecAssertions.assertOk(codec.encode(Transcoder.NBT, new HashSet<>(Arrays.asList("a", null)))));
        assertEquals(Set.of("a"), Assertions.assertDoesNotThrow(
                () -> CodecAssertions.assertOk(codec.decode(Transcoder.NBT, encoded))));
    }

    /**
     * An optional must fall back to its default when the value is absent, not when the inner codec rejects a value
     * that is really there. Swallowing the inner error makes orElse unreachable.
     */
    @Test
    public void optionalDoesNotSwallowInnerDecodeErrors() {
        final Codec<Integer> codec = Codec.INT.optional(7);
        Assertions.assertInstanceOf(Result.Error.class, codec.decode(Transcoder.NBT, Transcoder.NBT.createString("nope")));
        assertEquals(7, CodecAssertions.assertOk(codec.decode(Transcoder.NBT, Transcoder.NBT.createNull())));
    }

    @Test
    public void optionalFallsThroughToOrElse() {
        final Codec<Integer> codec = Codec.INT.optional().orElse(Codec.STRING.transform(Integer::parseInt, String::valueOf));
        assertEquals(12, CodecAssertions.assertOk(codec.decode(Transcoder.NBT, Transcoder.NBT.createString("12"))));
    }

    private static List<Arguments> optionalResults() {
        return nonDestructiveTranscoders().stream().flatMap(transcoder -> Stream.of(
                Arguments.of(transcoder, Codec.BOOLEAN, false),
                Arguments.of(transcoder, Codec.INT, 5125),
                Arguments.of(transcoder, Codec.LONG, 5125123L),
                Arguments.of(transcoder, Codec.FLOAT, 0.62143f),
                Arguments.of(transcoder, Codec.DOUBLE, 15.2d),
                Arguments.of(transcoder, Codec.BYTE, (byte) 7),
                Arguments.of(transcoder, Codec.SHORT, (short) 0),
                Arguments.of(transcoder, Codec.STRING, "scary")
        )).toList();
    }

    private static List<Transcoder<?>> nonDestructiveTranscoders() {
        return List.of(Transcoder.NBT, Transcoder.JSON, Transcoder.JAVA);
    }

    @ParameterizedTest
    @MethodSource("optionalResults")
    public <D, T> void testOptionalNullDecode(Transcoder<D> transcoder, Codec<T> codec, T expected) {
        var optionalCodec = codec.optional(expected);
        var result = optionalCodec.decode(transcoder, transcoder.createNull());
        CodecAssertions.assertOk(result);
        assertEquals(expected, result.orElseThrow());
    }

    @ParameterizedTest
    @MethodSource("optionalResults")
    public <D, T> void testOptionalEncodeDecodeValue(Transcoder<D> transcoder, Codec<T> codec, T expected) {
        var optionalCodec = codec.optional(expected);
        var encodeResult = optionalCodec.encode(transcoder, null);
        var result = optionalCodec.decode(transcoder, encodeResult.orElseThrow());
        CodecAssertions.assertOk(result);
        assertEquals(expected, result.orElseThrow());
    }

    @SuppressWarnings("DataFlowIssue")
    @ParameterizedTest
    @MethodSource("nonDestructiveTranscoders")
    <D> void testListUnmodifiable(Transcoder<D> transcoder) {
        List<String> testList = Arrays.asList("Hey", "How", "Are", "You");
        var codec = Codec.STRING.list();
        var encoded = codec.encode(transcoder, testList);
        CodecAssertions.assertOk(encoded);
        var decoded = codec.decode(transcoder, encoded.orElseThrow());
        CodecAssertions.assertOk(decoded);
        var decodedObject = decoded.orElseThrow();
        assertEquals(testList, decodedObject);
        Assertions.assertDoesNotThrow(() -> testList.set(0, "Test"));
        Assertions.assertNotEquals(testList, decodedObject);
        Assertions.assertThrows(UnsupportedOperationException.class, () -> decodedObject.set(0, "Test"));
    }

    @SuppressWarnings("DataFlowIssue")
    @ParameterizedTest
    @MethodSource("nonDestructiveTranscoders")
    <D> void testSetUnmodifiable(Transcoder<D> transcoder) {
        var testSet = new HashSet<>(Set.of("Hey", "How", "Are", "You"));
        var codec = Codec.STRING.set();
        var encoded = codec.encode(transcoder, testSet);
        CodecAssertions.assertOk(encoded);
        var decoded = codec.decode(transcoder, encoded.orElseThrow());
        CodecAssertions.assertOk(decoded);
        var decodedObject = decoded.orElseThrow();
        assertEquals(testSet, decodedObject);
        Assertions.assertDoesNotThrow(() -> testSet.remove("Hey"));
        Assertions.assertNotEquals(testSet, decodedObject);
        Assertions.assertThrows(UnsupportedOperationException.class, () -> decodedObject.remove("Hey"));
    }

    @SuppressWarnings("DataFlowIssue")
    @ParameterizedTest
    @MethodSource("nonDestructiveTranscoders")
    <D> void testMapUnmodifiable(Transcoder<D> transcoder) {
        var testSet = new HashMap<>(Map.of("Hey", "How", "Are", "You"));
        var codec = Codec.STRING.mapValue(Codec.STRING);
        var encoded = codec.encode(transcoder, testSet);
        CodecAssertions.assertOk(encoded);
        var decoded = codec.decode(transcoder, encoded.orElseThrow());
        CodecAssertions.assertOk(decoded);
        var decodedObject = decoded.orElseThrow();
        assertEquals(testSet, decodedObject);
        Assertions.assertDoesNotThrow(() -> testSet.remove("Hey"));
        Assertions.assertNotEquals(testSet, decodedObject);
        Assertions.assertThrows(UnsupportedOperationException.class, () -> decodedObject.remove("Hey"));
    }

    @Test
    void listOrSingleSingleValueAsSingle() {
        var codec = Codec.INT.listOrSingle();
        var value = codec.encode(Transcoder.NBT, List.of(42)).orElseThrow();
        assertEquals(intBinaryTag(42), value);
    }

    @Test
    void listOrSingleMultiValueAsList() {
        var codec = Codec.INT.listOrSingle();
        var value = codec.encode(Transcoder.NBT, List.of(42, 24)).orElseThrow();
        assertEquals(ListBinaryTag.builder()
                             .add(intBinaryTag(42))
                             .add(intBinaryTag(24))
                             .build(), value);
    }
}
