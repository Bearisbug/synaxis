package io.github.bearisbug.synaxis.command.builder.arguments.minecraft;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.utils.Range;

/**
 * Represents an argument which will give you an {@link Range.Float}.
 * <p>
 * Example: ..3, 3.., 5..10, 15
 */
public class ArgumentFloatRange extends ArgumentRange<Range.Float, Float> {

    public ArgumentFloatRange(String id) {
        super(id, Float::parseFloat, Range.Float::new);
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
