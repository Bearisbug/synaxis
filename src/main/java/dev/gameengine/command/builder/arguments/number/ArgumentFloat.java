package dev.gameengine.command.builder.arguments.number;

import dev.gameengine.command.ArgumentParserType;
import dev.gameengine.network.NetworkBuffer;

public class ArgumentFloat extends ArgumentNumber<Float> {

    public ArgumentFloat(String id) {
        super(id, ArgumentParserType.FLOAT, Float::parseFloat, (s, radix) -> (float) Integer.parseInt(s, radix),
                NetworkBuffer.FLOAT, Float::compare);
    }

    @Override
    public String toString() {
        return String.format("Float<%s>", getId());
    }
}
