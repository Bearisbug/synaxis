package io.github.bearisbug.synaxis.codec;

import com.google.gson.JsonParser;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.utils.Unit;
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
import java.util.UUID;
import java.util.stream.Stream;

import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertError;
import static net.kyori.adventure.nbt.IntBinaryTag.intBinaryTag;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertError("List size 2 exceeds maximum allowed size 1",
                Codec.STRING.list(1).encode(Transcoder.NBT, List.of("a", "b")));
        assertError("Set size 2 exceeds maximum allowed size 1",
                Codec.STRING.set(1).encode(Transcoder.NBT, Set.of("a", "b")));
        assertError("Map size 2 exceeds maximum allowed size 1",
                Codec.STRING.mapValue(Codec.STRING, 1).encode(Transcoder.NBT, Map.of("a", "b", "c", "d")));
    }

    /**
     * An oversized input names its own size next to the limit, so a rejected client payload shows how far over it
     * was.
     */
    @Test
    public void collectionDecodeOverMaxSizeNamesTheSize() {
        assertError("List size 2 exceeds maximum allowed size 1",
                Codec.STRING.list(1).decode(Transcoder.JAVA, List.of("a", "b")));
        assertError("Set size 2 exceeds maximum allowed size 1",
                Codec.STRING.set(1).decode(Transcoder.JAVA, List.of("a", "b")));
        assertError("Map size 2 exceeds maximum allowed size 1",
                Codec.STRING.mapValue(Codec.STRING, 1).decode(Transcoder.JAVA, Map.of("a", "b", "c", "d")));
    }

    /**
     * An element that does not decode names its index, and a map key that does not decode names the key, the way a
     * map value already did. Without it a long list only says what was wrong, not where.
     */
    @Test
    public void elementDecodeErrorNamesItsPosition() {
        assertError("1: Not a string: 1", Codec.STRING.list().decode(Transcoder.JAVA, List.of("a", 1)));
        assertError("1: Not a string: 1", Codec.STRING.set().decode(Transcoder.JAVA, List.of("a", 1)));
        assertError("k: Not an int: k", Codec.INT.mapValue(Codec.STRING).decode(Transcoder.JAVA, Map.of("k", "v")));
    }

    /**
     * An optional value decodes an explicit null to null, and a key codec may map a name it does not know to null.
     * {@code Map.copyOf} threw {@link NullPointerException} out of decode for both, past orElse and Either; the cached
     * typed map threw even earlier, from {@code computeIfAbsent(null)}.
     */
    @Test
    public void mapDecodeSkipsNullValuesAndRejectsNullKeys() {
        final Codec<Map<String, Integer>> optionalValues = Codec.STRING.mapValue(Codec.INT.optional());
        assertEquals(Map.of("b", 1), CodecAssertions.assertOk(Assertions.assertDoesNotThrow(() ->
                optionalValues.decode(Transcoder.JSON, JsonParser.parseString("{\"a\": null, \"b\": 1}")))));

        final Codec<String> knownNames = Codec.STRING.transform(Map.of("a", "a")::get, name -> name);
        final List<Codec<Map<String, Integer>>> codecs = List.of(knownNames.mapValue(Codec.INT),
                knownNames.<Integer>mapValueTyped(_ -> Codec.INT, true));
        for (Codec<Map<String, Integer>> codec : codecs) {
            assertError("x: map key decoded to null", Assertions.assertDoesNotThrow(() ->
                    codec.decode(Transcoder.JSON, JsonParser.parseString("{\"x\": 1}"))));
        }
    }

    /**
     * A UUID is exactly four ints. Five used to decode by dropping the last one, two failed with an index error; the
     * coerced codec reports the int array error when the string form does not decode either.
     */
    @Test
    public void uuidRejectsAnIntArrayOfAnotherLength() {
        for (int[] array : List.of(new int[]{1, 2}, new int[]{1, 2, 3, 4, 5})) {
            for (Codec<UUID> codec : List.of(Codec.UUID, Codec.UUID_COERCED)) {
                final String message = Assertions.assertInstanceOf(Result.Error.class,
                        codec.decode(Transcoder.NBT, Transcoder.NBT.createIntArray(array))).message();
                assertTrue(message.contains("4 entries, got " + array.length), message);
            }
        }
        final UUID uuid = new UUID(0x0102030405060708L, 0x090A0B0C0D0E0F10L);
        assertEquals(uuid, CodecAssertions.assertOk(Codec.UUID.decode(Transcoder.NBT,
                CodecAssertions.assertOk(Codec.UUID.encode(Transcoder.NBT, uuid)))));
    }

    /** When neither side decodes, both messages are reported as they are, not as the toString of the results. */
    @Test
    public void eitherReportsTheMessagesOfBothSides() {
        assertError("Failed to decode Either: left: Not an int: x; right: Not a boolean: x",
                Codec.Either(Codec.INT, Codec.BOOLEAN).decode(Transcoder.JAVA, "x"));
        final StructCodec<Integer> left = StructCodec.struct("a", Codec.INT, value -> value, value -> value);
        final StructCodec<Integer> right = StructCodec.struct("b", Codec.INT, value -> value, value -> value);
        assertError("Failed to decode Either: left: a: No such key: a; right: b: No such key: b",
                Codec.EitherStruct(left, right).decode(Transcoder.JAVA, Map.of()));
    }

    @Test
    public void unitIsAnEmptyMap() {
        final BinaryTag encoded = CodecAssertions.assertOk(Codec.UNIT.encode(Transcoder.NBT, Unit.INSTANCE));
        assertEquals(CompoundBinaryTag.empty(), encoded);
        assertEquals(Unit.INSTANCE, CodecAssertions.assertOk(Codec.UNIT.decode(Transcoder.NBT, encoded)));
    }

    /** The mapped fallback of orElse only decodes; a value the primary codec cannot encode keeps the primary error. */
    @Test
    public void orElseWithAMapperEncodesWithThePrimaryOnly() {
        final Codec<Integer> codec = Codec.INT.orElse(Codec.STRING, Integer::parseInt);
        assertEquals(12, CodecAssertions.assertOk(codec.decode(Transcoder.NBT, Transcoder.NBT.createString("12"))));
        assertError("null", codec.encode(Transcoder.NBT, null));
    }

    /**
     * A transcoder map can only be keyed by strings, and dropping the entries silently produced an empty map.
     */
    @Test
    public void mapWithNonStringKeysIsAnError() {
        Assertions.assertInstanceOf(Result.Error.class,
                Codec.INT.mapValue(Codec.STRING).encode(Transcoder.NBT, Map.of(1, "a")));
    }

    private static List<Arguments> mapsWithNonStringKeys() {
        return nonDestructiveTranscoders().stream().flatMap(transcoder -> Stream.of(
                Arguments.of(transcoder, Codec.INT.mapValue(Codec.STRING)),
                Arguments.of(transcoder, Codec.INT.<String>mapValueTyped(_ -> Codec.STRING)),
                Arguments.of(transcoder, Codec.INT.<String>mapValueTyped(_ -> Codec.STRING, true))
        )).toList();
    }

    /**
     * Both kinds of map codec refuse a key that is not a string in every format. The typed one used to end up with an
     * empty map in NBT and Java, which drop such a key, and with the number written as a string in JSON.
     */
    @ParameterizedTest
    @MethodSource("mapsWithNonStringKeys")
    public <D> void mapWithNonStringKeysIsAnErrorInEveryFormat(Transcoder<D> transcoder, Codec<Map<Integer, String>> codec) {
        Assertions.assertInstanceOf(Result.Error.class,
                Assertions.assertDoesNotThrow(() -> codec.encode(transcoder, Map.of(1, "a"))));
    }

    /**
     * The item hash encodes map components with the CRC32 transcoder, which reads nothing back. A map codec that read
     * each encoded key back with getString failed there for every map, the enchantments of an item among them.
     */
    @Test
    public void mapEncodesWithTheHashTranscoder() {
        final Transcoder<Integer> hash = Transcoder.CRC32_HASH;
        final Integer expected = hash.createMap().put("a", hash.createInt(1)).build();

        assertEquals(expected, CodecAssertions.assertOk(Codec.STRING.mapValue(Codec.INT).encode(hash, Map.of("a", 1))));
        assertEquals(expected, CodecAssertions.assertOk(
                Codec.STRING.<Integer>mapValueTyped(_ -> Codec.INT).encode(hash, Map.of("a", 1))));
    }

    @Test
    public void mapValueDecodeErrorNamesTheKey() {
        final String message = Assertions.assertInstanceOf(Result.Error.class,
                Codec.STRING.mapValue(Codec.INT).decode(Transcoder.JAVA, Map.of("a", "x"))).message();
        assertTrue(message.startsWith("a: "), message);
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

    /**
     * An element equal to the default of an optional codec is still an element: encoded as the absent value, it was
     * dropped from the list, the set or the map, so [0, 1, 0] came back as [1].
     */
    @ParameterizedTest
    @MethodSource("nonDestructiveTranscoders")
    public <D> void optionalWithDefaultInsideCollectionsKeepsDefaultElements(Transcoder<D> transcoder) {
        final Codec<List<Integer>> list = Codec.INT.optional(0).list();
        assertEquals(List.of(0, 1, 0), CodecAssertions.assertOk(
                list.decode(transcoder, CodecAssertions.assertOk(list.encode(transcoder, List.of(0, 1, 0))))));

        final Codec<Set<Integer>> set = Codec.INT.optional(0).set();
        assertEquals(Set.of(0, 1), CodecAssertions.assertOk(
                set.decode(transcoder, CodecAssertions.assertOk(set.encode(transcoder, Set.of(0, 1))))));

        final Map<String, Integer> map = Map.of("a", 0, "b", 1);
        final Codec<Map<String, Integer>> mapValue = Codec.STRING.mapValue(Codec.INT.optional(0));
        assertEquals(map, CodecAssertions.assertOk(
                mapValue.decode(transcoder, CodecAssertions.assertOk(mapValue.encode(transcoder, map)))));
        final Codec<Map<String, Integer>> mapValueTyped = Codec.STRING.<Integer>mapValueTyped(_ -> Codec.INT.optional(0));
        assertEquals(map, CodecAssertions.assertOk(
                mapValueTyped.decode(transcoder, CodecAssertions.assertOk(mapValueTyped.encode(transcoder, map)))));
    }

    /**
     * The network type floors a fractional coordinate. Truncating it towards zero put a negative one a block off in
     * every format of the codec, the item hash and the stored item among them.
     */
    @ParameterizedTest
    @MethodSource("nonDestructiveTranscoders")
    public <D> void blockPositionFloorsLikeTheNetworkType(Transcoder<D> transcoder) {
        final Point position = new Vec(-0.5, 64.2, -10.7);
        final Point decoded = CodecAssertions.assertOk(Codec.BLOCK_POSITION.decode(transcoder,
                CodecAssertions.assertOk(Codec.BLOCK_POSITION.encode(transcoder, position))));
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer();
        buffer.write(NetworkBuffer.BLOCK_POSITION, position);
        final Point network = buffer.read(NetworkBuffer.BLOCK_POSITION);

        assertArrayEquals(new int[]{-1, 64, -11}, new int[]{decoded.blockX(), decoded.blockY(), decoded.blockZ()});
        assertArrayEquals(new int[]{network.blockX(), network.blockY(), network.blockZ()},
                new int[]{decoded.blockX(), decoded.blockY(), decoded.blockZ()});
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
    void listOrSingleRoundTrips() {
        var codec = Codec.STRING.listOrSingle();
        for (List<String> list : List.of(List.<String>of(), List.of("a"), List.of("a", "b"))) {
            assertEquals(list, CodecAssertions.assertOk(codec.decode(Transcoder.NBT,
                    CodecAssertions.assertOk(codec.encode(Transcoder.NBT, list)))));
        }
        assertEquals(List.of("a"), CodecAssertions.assertOk(codec.decode(Transcoder.NBT, Transcoder.NBT.createString("a"))));
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
