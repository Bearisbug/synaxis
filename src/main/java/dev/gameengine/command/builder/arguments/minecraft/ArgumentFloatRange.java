package dev.gameengine.command.builder.arguments.minecraft;

import dev.gameengine.command.ArgumentParserType;
import dev.gameengine.utils.Range;

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
