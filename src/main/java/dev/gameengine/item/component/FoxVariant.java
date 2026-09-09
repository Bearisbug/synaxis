package dev.gameengine.item.component;

import dev.gameengine.codec.Codec;
import dev.gameengine.network.NetworkBuffer;

public enum FoxVariant {
    RED,
    SNOW;

    public static final NetworkBuffer.Type<FoxVariant> NETWORK_TYPE = NetworkBuffer.Enum(FoxVariant.class);
    public static final Codec<FoxVariant> CODEC = Codec.Enum(FoxVariant.class);
}
