package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

public enum SalmonSize {
    SMALL,
    MEDIUM,
    LARGE;

    public static final NetworkBuffer.Type<SalmonSize> NETWORK_TYPE = NetworkBuffer.Enum(SalmonSize.class);
    public static final Codec<SalmonSize> CODEC = Codec.Enum(SalmonSize.class);
}
