package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

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
