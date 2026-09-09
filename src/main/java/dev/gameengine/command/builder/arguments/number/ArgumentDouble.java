package dev.gameengine.command.builder.arguments.number;

import dev.gameengine.command.ArgumentParserType;
import dev.gameengine.network.NetworkBuffer;

public class ArgumentDouble extends ArgumentNumber<Double> {

    public ArgumentDouble(String id) {
        super(id, ArgumentParserType.DOUBLE, Double::parseDouble, (s, radix) -> (double) Long.parseLong(s, radix),
                NetworkBuffer.DOUBLE, Double::compare);
    }

    @Override
    public String toString() {
        return String.format("Double<%s>", getId());
    }
}
