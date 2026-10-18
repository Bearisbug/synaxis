package io.github.bearisbug.synaxis.command.builder.arguments.minecraft;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.utils.Range;

/**
 * Represents an argument which will give you an {@link Range.Float}.
 * <p>
 * Example: ..3, 3.., 5..10, 15
 * <p>
 * Both bounds have to be finite numbers: NaN and the infinities fail with {@link #FORMAT_ERROR}.
 */
public class ArgumentFloatRange extends ArgumentRange<Range.Float, Float> {

    public ArgumentFloatRange(String id) {
        super(id, ArgumentFloatRange::parseFinite, Range.Float::new);
    }

    private static Float parseFinite(String input) {
        final float value = Float.parseFloat(input);
        if (!Float.isFinite(value)) throw new NumberFormatException("Not a finite number: " + input);
        return value;
    }

    @Override
    public ArgumentParserType parser() {
        return ArgumentParserType.FLOAT_RANGE;
    }

    @Override
    public String toString() {
        return String.format("FloatRange<%s>", getId());
    }
}
