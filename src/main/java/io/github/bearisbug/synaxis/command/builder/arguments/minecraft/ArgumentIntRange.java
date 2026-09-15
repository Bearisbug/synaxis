package io.github.bearisbug.synaxis.command.builder.arguments.minecraft;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.utils.Range;

/**
 * Represents an argument which will give you an {@link Range.Int}.
 * <p>
 * Example: ..3, 3.., 5..10, 15
 */
public class ArgumentIntRange extends ArgumentRange<Range.Int, Integer> {

    public ArgumentIntRange(String id) {
        super(id, Integer::parseInt, Range.Int::new);
    }

    @Override
    public ArgumentParserType parser() {
        return ArgumentParserType.INT_RANGE;
    }

    @Override
    public String toString() {
        return String.format("IntRange<%s>", getId());
    }
}
