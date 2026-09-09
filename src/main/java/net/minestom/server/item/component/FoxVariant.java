package net.minestom.server.item.component;

import net.minestom.server.codec.Codec;
import net.minestom.server.network.NetworkBuffer;

public enum FoxVariant {
    RED,
    SNOW;

    public static final NetworkBuffer.Type<FoxVariant> NETWORK_TYPE = NetworkBuffer.Enum(FoxVariant.class);
    public static final Codec<FoxVariant> CODEC = Codec.Enum(FoxVariant.class);
}
