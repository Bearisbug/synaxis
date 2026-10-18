package io.github.bearisbug.synaxis.command.builder.arguments.number;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

/**
 * Represents a float. Only a finite value is accepted: NaN and the infinities, also an input that overflows to
 * one such as {@code 1e40}, fail with {@link #NOT_NUMBER_ERROR}.
 */
public class ArgumentFloat extends ArgumentNumber<Float> {

    public ArgumentFloat(String id) {
        super(id, ArgumentParserType.FLOAT, Float::parseFloat, NetworkBuffer.FLOAT, Float::compare);
    }

    @Override
    public String toString() {
        return String.format("Float<%s>", getId());
    }
}
