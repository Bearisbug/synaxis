package io.github.bearisbug.synaxis.command.builder.arguments.number;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

public class ArgumentLong extends ArgumentNumber<Long> {

    public ArgumentLong(String id) {
        super(id, ArgumentParserType.LONG, Long::parseLong, NetworkBuffer.LONG, Long::compare);
    }

    @Override
    public String toString() {
        return String.format("Long<%s>", getId());
    }
}
