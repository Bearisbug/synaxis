package dev.gameengine.item.component;

import dev.gameengine.codec.Codec;
import dev.gameengine.network.NetworkBuffer;

public enum AxolotlVariant {
    LUCY,
    WILD,
    GOLD,
    CYAN,
    BLUE;

    public static final NetworkBuffer.Type<AxolotlVariant> NETWORK_TYPE = NetworkBuffer.Enum(AxolotlVariant.class);
    public static final Codec<AxolotlVariant> CODEC = Codec.Enum(AxolotlVariant.class);
}
