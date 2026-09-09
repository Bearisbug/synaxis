package dev.gameengine.item.component;

import dev.gameengine.codec.Codec;
import dev.gameengine.network.NetworkBuffer;

public enum MooshroomVariant {
    RED,
    BROWN;

    public static final NetworkBuffer.Type<MooshroomVariant> NETWORK_TYPE = NetworkBuffer.Enum(MooshroomVariant.class);
    public static final Codec<MooshroomVariant> CODEC = Codec.Enum(MooshroomVariant.class);
}
