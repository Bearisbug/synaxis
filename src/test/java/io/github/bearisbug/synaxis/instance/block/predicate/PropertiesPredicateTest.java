package io.github.bearisbug.synaxis.instance.block.predicate;

import io.github.bearisbug.synaxis.instance.block.Block;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    }
}
