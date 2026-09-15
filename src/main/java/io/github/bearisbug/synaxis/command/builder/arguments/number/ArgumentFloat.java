package io.github.bearisbug.synaxis.command.builder.arguments.number;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

public class ArgumentFloat extends ArgumentNumber<Float> {

    public ArgumentFloat(String id) {
        super(id, ArgumentParserType.FLOAT, Float::parseFloat, NetworkBuffer.FLOAT, Float::compare);
    }

    @Override
    public String toString() {
        return String.format("Float<%s>", getId());
    }
}
