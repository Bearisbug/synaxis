package io.github.bearisbug.synaxis.command.builder.arguments.number;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

/**
 * Represents a double. Only a finite value is accepted: NaN and the infinities, also an input that overflows to
 * one such as {@code 1e400}, fail with {@link #NOT_NUMBER_ERROR}.
 */
public class ArgumentDouble extends ArgumentNumber<Double> {

    public ArgumentDouble(String id) {
        super(id, ArgumentParserType.DOUBLE, Double::parseDouble, NetworkBuffer.DOUBLE, Double::compare);
    }

    @Override
    public String toString() {
        return String.format("Double<%s>", getId());
    }
}
