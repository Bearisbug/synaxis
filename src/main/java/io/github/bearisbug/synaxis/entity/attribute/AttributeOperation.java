package io.github.bearisbug.synaxis.entity.attribute;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import org.jetbrains.annotations.Nullable;

public enum AttributeOperation {
    // Neither the order nor the names can change: NETWORK_TYPE writes the ordinal as the protocol id, and
    // CODEC writes the lower case name
    ADD_VALUE(0),
    ADD_MULTIPLIED_BASE(1),
    ADD_MULTIPLIED_TOTAL(2);

    public static final NetworkBuffer.Type<AttributeOperation> NETWORK_TYPE = NetworkBuffer.Enum(AttributeOperation.class);
    public static final Codec<AttributeOperation> CODEC = Codec.Enum(AttributeOperation.class);

    private static final AttributeOperation[] VALUES = new AttributeOperation[]{ADD_VALUE, ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL};
    private final int id;

    AttributeOperation(int id) {
        this.id = id;
    }

    public int getId() {
        return this.id;
    }

    public static @Nullable AttributeOperation fromId(int id) {
        if (id >= 0 && id < VALUES.length) {
            return VALUES[id];
        }
        return null;
    }
}
