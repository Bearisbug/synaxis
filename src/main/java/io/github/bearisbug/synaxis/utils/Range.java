package io.github.bearisbug.synaxis.utils;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import org.jetbrains.annotations.Nullable;

/**
 * Represents the base for any data type that is numeric.
 *
 * @param <T> The type numeric of the range object.
 */
public sealed interface Range<T extends Number> {
    record Int(@Nullable Integer min, @Nullable Integer max) implements Range<Integer> {
        private static final Codec<Range.Int> STRUCT_CODEC = StructCodec.struct(
                "min", Codec.INT.optional(), Range.Int::min,
                "max", Codec.INT.optional(), Range.Int::max,
                Range.Int::new
        );
        public static final Codec<Range.Int> CODEC = Codec.Either(STRUCT_CODEC, Codec.INT).transform(
                either -> either.unify(range -> range, Range.Int::new),
                range -> range.min != null && range.min.equals(range.max) ? Either.right(range.min) : Either.left(range));

        public Int {
            if (min != null && max != null && min > max)
                throw new IllegalArgumentException("min (" + min + ") must not exceed max (" + max + ")");
        }

        public Int(@Nullable Integer value) {
            this(value, value);
        }

        public boolean inRange(int value) {
            return (min == null || value >= min) && (max == null || value <= max);
        }
    }

    record Float(@Nullable java.lang.Float min, @Nullable java.lang.Float max) implements Range<java.lang.Float> {
        public Float {
            if (min != null && max != null && min > max)
                throw new IllegalArgumentException("min (" + min + ") must not exceed max (" + max + ")");
        }

        public Float(@Nullable java.lang.Float value) {
            this(value, value);
        }

        public boolean inRange(float value) {
            return (min == null || value >= min) && (max == null || value <= max);
        }
    }

    record Double(@Nullable java.lang.Double min, @Nullable java.lang.Double max) implements Range<java.lang.Double> {
        private static final Codec<Range.Double> STRUCT_CODEC = StructCodec.struct(
                "min", Codec.DOUBLE.optional(), Range.Double::min,
                "max", Codec.DOUBLE.optional(), Range.Double::max,
                Range.Double::new
        );
        public static final Codec<Range.Double> CODEC = Codec.Either(STRUCT_CODEC, Codec.DOUBLE).transform(
                either -> either.unify(range -> range, Range.Double::new),
                range -> range.min != null && range.min.equals(range.max) ? Either.right(range.min) : Either.left(range));

        public Double {
            if (min != null && max != null && min > max)
                throw new IllegalArgumentException("min (" + min + ") must not exceed max (" + max + ")");
        }

        public Double(@Nullable java.lang.Double value) {
            this(value, value);
        }

        public boolean inRange(double value) {
            return (min == null || value >= min) && (max == null || value <= max);
        }
    }
}
