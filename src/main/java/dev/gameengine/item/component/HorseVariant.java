package dev.gameengine.item.component;

import dev.gameengine.codec.Codec;
import dev.gameengine.network.NetworkBuffer;

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
