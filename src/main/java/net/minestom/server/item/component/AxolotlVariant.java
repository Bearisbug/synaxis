package net.minestom.server.item.component;

import net.minestom.server.codec.Codec;
import net.minestom.server.network.NetworkBuffer;

public enum AxolotlVariant {
    LUCY,
    WILD,
    GOLD,
    CYAN,
    BLUE;

    public static final NetworkBuffer.Type<AxolotlVariant> NETWORK_TYPE = NetworkBuffer.Enum(AxolotlVariant.class);
    public static final Codec<AxolotlVariant> CODEC = Codec.Enum(AxolotlVariant.class);
}
