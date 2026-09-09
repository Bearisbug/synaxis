package net.minestom.server.item.component;

import net.minestom.server.codec.Codec;
import net.minestom.server.network.NetworkBuffer;

public enum HorseVariant {
    WHITE,
    CREAMY,
    CHESTNUT,
    BROWN,
    BLACK,
    GRAY,
    DARK_BROWN;

    public static final NetworkBuffer.Type<HorseVariant> NETWORK_TYPE = NetworkBuffer.Enum(HorseVariant.class);
    public static final Codec<HorseVariant> CODEC = Codec.Enum(HorseVariant.class);
}
