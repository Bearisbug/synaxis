package io.github.bearisbug.synaxis.command.builder.arguments.number;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

public class ArgumentDouble extends ArgumentNumber<Double> {

    public ArgumentDouble(String id) {
        super(id, ArgumentParserType.DOUBLE, Double::parseDouble, NetworkBuffer.DOUBLE, Double::compare);
    }

    @Override
    public String toString() {
        return String.format("Double<%s>", getId());
    }
}
