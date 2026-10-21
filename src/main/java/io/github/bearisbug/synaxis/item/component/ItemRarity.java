package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

public enum ItemRarity {
    COMMON,
    UNCOMMON,
    RARE,
    EPIC;

    public static final NetworkBuffer.Type<ItemRarity> NETWORK_TYPE = NetworkBuffer.Enum(ItemRarity.class);
    public static final Codec<ItemRarity> CODEC = Codec.Enum(ItemRarity.class);
}
