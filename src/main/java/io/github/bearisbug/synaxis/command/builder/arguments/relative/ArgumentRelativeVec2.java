package io.github.bearisbug.synaxis.command.builder.arguments.relative;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.coordinate.Vec;

import java.util.function.Function;

/**
 * Represents a {@link Vec} with 2 floating numbers (x;z) which can take relative coordinates.
 * <p>
 * Example: -1.2 ~
 */
public class ArgumentRelativeVec2 extends ArgumentRelativeVec {

    public ArgumentRelativeVec2(String id) {
        super(id, 2);
    }

    @Override
    public ArgumentParserType parser() {
        return ArgumentParserType.VEC2;
    }

    @Override
    public String toString() {
        return String.format("RelativeVec2<%s>", getId());
    }

    @Override
    Function<String, ? extends Number> getRelativeNumberParser() {
        return Double::parseDouble;
    }

    @Override
    Function<String, ? extends Number> getAbsoluteNumberParser() {
        return Double::parseDouble;
    }
}
