package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

public enum AxolotlVariant {
    LUCY,
    WILD,
    GOLD,
    CYAN,
    BLUE;

    public static final NetworkBuffer.Type<AxolotlVariant> NETWORK_TYPE = NetworkBuffer.Enum(AxolotlVariant.class);
    public static final Codec<AxolotlVariant> CODEC = Codec.Enum(AxolotlVariant.class);
}
