package dev.gameengine.command.builder.arguments.number;

import dev.gameengine.command.ArgumentParserType;
import dev.gameengine.network.NetworkBuffer;

public class ArgumentInteger extends ArgumentNumber<Integer> {

    public ArgumentInteger(String id) {
        super(id, ArgumentParserType.INTEGER, Integer::parseInt, NetworkBuffer.INT, Integer::compare);
    }

    @Override
    public String toString() {
        return String.format("Integer<%s>", getId());
    }
}
