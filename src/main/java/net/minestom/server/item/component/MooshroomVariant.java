package net.minestom.server.item.component;

import net.minestom.server.codec.Codec;
import net.minestom.server.network.NetworkBuffer;

public enum MooshroomVariant {
    RED,
    BROWN;

    public static final NetworkBuffer.Type<MooshroomVariant> NETWORK_TYPE = NetworkBuffer.Enum(MooshroomVariant.class);
    public static final Codec<MooshroomVariant> CODEC = Codec.Enum(MooshroomVariant.class);
}
