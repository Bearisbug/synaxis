package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

public enum MooshroomVariant {
    RED,
    BROWN;

    public static final NetworkBuffer.Type<MooshroomVariant> NETWORK_TYPE = NetworkBuffer.Enum(MooshroomVariant.class);
    public static final Codec<MooshroomVariant> CODEC = Codec.Enum(MooshroomVariant.class);
}
