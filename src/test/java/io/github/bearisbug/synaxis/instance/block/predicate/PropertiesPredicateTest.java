package io.github.bearisbug.synaxis.instance.block.predicate;

import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.instance.block.Block;
import net.kyori.adventure.nbt.IntBinaryTag;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

public class PropertiesPredicateTest {

    @Test
    public void testMultiMatch() {
        var predicate = new PropertiesPredicate(Map.of("facing", new PropertiesPredicate.ValuePredicate.Exact("east"),
                "shape", new PropertiesPredicate.ValuePredicate.Exact("inner_left")));
        assertTrue(predicate.test(Block.STONE_STAIRS.withProperties(Map.of("facing", "east", "shape", "inner_left"))));
        assertFalse(predicate.test(Block.STONE_STAIRS.withProperties(Map.of("facing", "east"))));
        assertFalse(predicate.test(Block.STONE));
    }

    @Nested
    class ValuePredicate {

        private static Stream<Arguments> exactTests() {
            return Stream.of(
                    // name, expected, actual, valid
                    arguments("success", "value", "value", true),
                    arguments("fail", "value", "other", false),
                    arguments("missing act", "value", null, false)
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("exactTests")
        public void matchExact(String name, String expected, String actual, boolean valid) {
            var predicate = new PropertiesPredicate.ValuePredicate.Exact(expected);
            assertEquals(valid, predicate.test(actual));
        }

        private static Stream<Arguments> rangeTests() {
            return Stream.of(
                    // name, min, max, value, valid
                    arguments("int / min exact", "0", null, "0", true),
                    arguments("int / min too low (inclusive)", "1", null, "0", false),
                    arguments("int / max exact", null, "1", "0", true),
                    arguments("int / max inclusive", null, "1", "1", true),
                    arguments("int / max too high", null, "1", "2", false),
                    arguments("int / no bounds", null, null, "3", true),
                    arguments("int / bound not an int", "1", "x", "3", false),
                    arguments("int / range good a", "0", "2", "1", true),
                    arguments("int / range good b", "0", "20", "11", true),
                    arguments("int / range too low", "0", "2", "-1", false),
                    arguments("int / range too high", "0", "2", "3", false),

                    arguments("string / min exact", "a", null, "a", true),
                    arguments("string / max exact", null, "b", "a", true),
                    arguments("string / max inclusive", null, "b", "b", true),
                    arguments("missing / no bounds", null, null, null, false),
                    arguments("string / range good", "c", "g", "e", true),
                    arguments("string / range bad low", "c", "g", "a", false),
                    arguments("string / range bad high", "c", "g", "z", false)
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("rangeTests")
        public void matchRange(String name, String min, String max, String value, boolean valid) {
            var predicate = new PropertiesPredicate.ValuePredicate.Range(min, max);
            assertEquals(valid, predicate.test(value));
        }

        /** An exact match against no value matches nothing, which leaving the property out says plainly. */
        @Test
        public void exactRejectsNoValue() {
            final @Nullable String absent = null;
            final NullPointerException error = assertThrows(NullPointerException.class,
                    () -> new PropertiesPredicate.ValuePredicate.Exact(absent));
            assertTrue(error.getMessage().contains("omit the property"), error.getMessage());
        }

        /** The client takes both bounds of a range inclusively, so the oldest wheat is in the range of its ages. */
        @Test
        public void rangeIncludesTheMaximumOfABlockProperty() {
            var predicate = new PropertiesPredicate(Map.of("age", new PropertiesPredicate.ValuePredicate.Range("0", "7")));
            assertTrue(predicate.test(Block.WHEAT.withProperty("age", "7")));
            assertTrue(predicate.test(Block.WHEAT.withProperty("age", "0")));
            assertFalse(predicate.test(Block.STONE));
        }

        /** The client orders an enum property as its enum is declared: half has top before bottom. */
        @Test
        public void enumRangeFollowsDeclarationOrder() {
            var predicate = range("half", "top", "bottom");
            assertTrue(predicate.test(Block.OAK_STAIRS.withProperty("half", "bottom")));
            assertTrue(predicate.test(Block.OAK_STAIRS.withProperty("half", "top")));
        }

        /** Directions go down, up, north, south, west, east, which the six way facing does not list them in. */
        @Test
        public void directionRangeFollowsTheDirectionOrder() {
            var facing = range("facing", "up", "north");
            assertTrue(facing.test(Block.DISPENSER.withProperty("facing", "up")));
            assertTrue(facing.test(Block.DISPENSER.withProperty("facing", "north")));
            assertFalse(facing.test(Block.DISPENSER.withProperty("facing", "down")));
            assertFalse(facing.test(Block.DISPENSER.withProperty("facing", "east")));

            var vertical = range("vertical_direction", "down", "up");
            assertTrue(vertical.test(Block.POINTED_DRIPSTONE.withProperty("vertical_direction", "up")));
            assertTrue(vertical.test(Block.POINTED_DRIPSTONE.withProperty("vertical_direction", "down")));
        }

        /** The client puts false before true. */
        @Test
        public void booleanRangeHasFalseFirst() {
            var predicate = range("waterlogged", "false", "true");
            assertTrue(predicate.test(Block.OAK_STAIRS.withProperty("waterlogged", "true")));
            assertTrue(predicate.test(Block.OAK_STAIRS.withProperty("waterlogged", "false")));
            assertFalse(range("waterlogged", "true", "false").test(Block.OAK_STAIRS));
        }

        /** A bound that is no value of the property matches nothing, the way the client reads it. */
        @Test
        public void ageRangeWithAnInvalidBoundMatchesNothing() {
            var wheat = Block.WHEAT.withProperty("age", "3");
            assertFalse(range("age", "-1", "7").test(wheat));
            assertFalse(range("age", null, "8").test(wheat));
            assertTrue(range("age", "2", null).test(wheat));
            assertFalse(range("facing", "up", null).test(Block.HOPPER.withProperty("facing", "west")));
        }

        /** The client parses a bound of an integer property with Integer.parseInt: "05" and "+5" are 5, "5.0" is none. */
        @Test
        public void integerBoundsReadAsTheClientParsesThem() {
            var five = range("age", "5", null);
            var seven = range("age", null, "7");
            for (int age = 0; age <= 7; age++) {
                var wheat = Block.WHEAT.withProperty("age", String.valueOf(age));
                assertEquals(five.test(wheat), range("age", "05", null).test(wheat), "min 05, age " + age);
                assertEquals(five.test(wheat), range("age", "+5", null).test(wheat), "min +5, age " + age);
                assertEquals(seven.test(wheat), range("age", null, "07").test(wheat), "max 07, age " + age);
                assertFalse(range("age", "5.0", null).test(wheat), "min 5.0, age " + age);
                assertFalse(range("age", "x", null).test(wheat), "min x, age " + age);
            }
        }

        /** A value that reads as neither form names the failure of both. */
        @Test
        public void decodeFailureNamesBothForms() {
            final Result<PropertiesPredicate.ValuePredicate> result =
                    PropertiesPredicate.ValuePredicate.CODEC.decode(Transcoder.NBT, IntBinaryTag.intBinaryTag(3));
            final String message = assertInstanceOf(Result.Error.class, result).message();
            assertTrue(message.contains("Failed to decode Either"), message);

            assertEquals(new PropertiesPredicate.ValuePredicate.Range("1", null),
                    assertOk(PropertiesPredicate.ValuePredicate.CODEC.decode(Transcoder.JSON, JsonParser.parseString("{\"min\":\"1\"}"))));
            assertEquals(new PropertiesPredicate.ValuePredicate.Exact("east"),
                    assertOk(PropertiesPredicate.ValuePredicate.CODEC.decode(Transcoder.JSON, JsonParser.parseString("\"east\""))));
        }

        private static PropertiesPredicate range(String key, @Nullable String min, @Nullable String max) {
            return new PropertiesPredicate(Map.of(key, new PropertiesPredicate.ValuePredicate.Range(min, max)));
        }

    }
}
