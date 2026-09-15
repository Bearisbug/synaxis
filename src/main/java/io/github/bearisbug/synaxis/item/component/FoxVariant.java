package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

public enum FoxVariant {
    RED,
    SNOW;

    public static final NetworkBuffer.Type<FoxVariant> NETWORK_TYPE = NetworkBuffer.Enum(FoxVariant.class);
    public static final Codec<FoxVariant> CODEC = Codec.Enum(FoxVariant.class);
}
