package io.github.bearisbug.synaxis.instance.block.predicate;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.utils.Either;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;

public record PropertiesPredicate(Map<String, ValuePredicate> properties) implements Predicate<Block> {

    public static final NetworkBuffer.Type<PropertiesPredicate> NETWORK_TYPE = NetworkBufferTemplate.template(
            NetworkBuffer.STRING.mapValue(ValuePredicate.NETWORK_TYPE), PropertiesPredicate::properties,
            PropertiesPredicate::new
    );
    public static final Codec<PropertiesPredicate> CODEC = Codec.STRING.mapValue(ValuePredicate.CODEC)
            .transform(PropertiesPredicate::new, PropertiesPredicate::properties);

    public static PropertiesPredicate exact(String key, String value) {
        return new PropertiesPredicate(Map.of(key, new ValuePredicate.Exact(value)));
    }

    public PropertiesPredicate {
        properties = Map.copyOf(properties);
    }

    @Override
    public boolean test(Block block) {
        for (Map.Entry<String, ValuePredicate> entry : properties.entrySet()) {
            final boolean matches = entry.getValue() instanceof ValuePredicate.Range range
                    ? range.test(block, entry.getKey())
                    : entry.getValue().test(block.getProperty(entry.getKey()));
            if (!matches)
                return false;
        }
        return true;
    }

    public sealed interface ValuePredicate extends Predicate<@Nullable String> permits ValuePredicate.Exact, ValuePredicate.Range {
        NetworkBuffer.Type<ValuePredicate> NETWORK_TYPE = NetworkBuffer.Either(Exact.NETWORK_TYPE, Range.NETWORK_TYPE)
                .transform(Either::identity, ValuePredicate::split);
        Codec<ValuePredicate> CODEC = Codec.Either(Exact.CODEC, Range.CODEC).transform(Either::identity, ValuePredicate::split);

        private static Either<Exact, Range> split(ValuePredicate predicate) {
            return switch (predicate) {
                case Exact exact -> Either.left(exact);
                case Range range -> Either.right(range);
            };
        }

        record Exact(String value) implements ValuePredicate {

            public static final NetworkBuffer.Type<Exact> NETWORK_TYPE = NetworkBuffer.STRING.transform(Exact::new, Exact::value);
            public static final Codec<Exact> CODEC = Codec.STRING.transform(Exact::new, Exact::value);

            public Exact {
                Objects.requireNonNull(value, "Exact value; omit the property from PropertiesPredicate to not constrain it");
            }

            @Override
            public boolean test(@Nullable String prop) {
                return prop != null && prop.equals(value);
            }
        }

        /**
         * <p>Matches a property whose value lies between {@code min} and {@code max}, both inclusive.</p>
         *
         * <p>The property of a block, in {@link PropertiesPredicate#test(Block)} and {@link #test(Block, String)}, is
         * compared in its own order, the one the client compares it in: integers by value, booleans false before
         * true, directions as down, up, north, south, west, east, other enum constants in the order the registry
         * lists them; a bound that is no value of the property matches nothing.</p>
         *
         * <p>Without either bound, the property only has to exist.</p>
         *
         * @param min The min value to match, inclusive
         * @param max The max value to match, inclusive
         */
        record Range(@Nullable String min, @Nullable String max) implements ValuePredicate {
            public static final NetworkBuffer.Type<Range> NETWORK_TYPE = NetworkBufferTemplate.template(
                    STRING.optional(), Range::min,
                    STRING.optional(), Range::max,
                    Range::new);
            public static final Codec<Range> CODEC = StructCodec.struct(
                    "min", Codec.STRING.optional(), Range::min,
                    "max", Codec.STRING.optional(), Range::max,
                    Range::new);

            // The order of the Direction enum of the client
            private static final List<String> DIRECTIONS = List.of("down", "up", "north", "south", "west", "east");

            /**
             * Tests the property {@code key} of {@code block}, in the order of the property, see {@link Range}.
             *
             * @param block the block
             * @param key   the property name
             * @return false if the block has no such property, a bound is no value of it, or its value is out of range
             */
            public boolean test(Block block, String key) {
                final List<String> values = block.propertyValues(key);
                if (values == null) return false;
                final List<String> order = order(values);
                final int value = order.indexOf(block.getProperty(key));
                return (min == null || (values.contains(min) && value >= order.indexOf(min)))
                        && (max == null || (values.contains(max) && value <= order.indexOf(max)));
            }

            // The order the client compares the values of a property in: the registry lists booleans true first, and
            // the six way facing and vertical_direction list their directions out of the order of the enum
            private static List<String> order(List<String> values) {
                if (values.equals(List.of("true", "false"))) return List.of("false", "true");
                return DIRECTIONS.containsAll(values) ? DIRECTIONS : values;
            }

            /**
             * Tests a value without its block, so without the order of its property: a value that parses as an
             * integer is compared as one, and then a bound that does not parse matches nothing; any other value is
             * compared as a string. The property of a block is matched by {@link #test(Block, String)}.
             */
            @Override
            public boolean test(@Nullable String prop) {
                if (prop == null) return false;
                if (min == null && max == null) return true;
                final int value;
                try {
                    value = Integer.parseInt(prop);
                } catch (NumberFormatException _) {
                    // Not an integer, just compare the strings
                    return (min == null || prop.compareTo(min) >= 0)
                            && (max == null || prop.compareTo(max) <= 0);
                }
                try {
                    return (min == null || value >= Integer.parseInt(min))
                            && (max == null || value <= Integer.parseInt(max));
                } catch (NumberFormatException _) {
                    // A bound that is no value of an integer property
                    return false;
                }
            }
        }
    }
}
