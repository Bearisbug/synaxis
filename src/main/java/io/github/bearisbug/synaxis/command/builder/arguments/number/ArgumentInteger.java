package io.github.bearisbug.synaxis.command.builder.arguments.number;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

public class ArgumentInteger extends ArgumentNumber<Integer> {

    public ArgumentInteger(String id) {
        super(id, ArgumentParserType.INTEGER, Integer::parseInt, NetworkBuffer.INT, Integer::compare);
    }

    @Override
    public String toString() {
        return String.format("Integer<%s>", getId());
    }
}
