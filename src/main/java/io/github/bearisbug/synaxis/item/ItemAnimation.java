package io.github.bearisbug.synaxis.item;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

public enum ItemAnimation {
    NONE,
    EAT,
    DRINK,
    BLOCK,
    BOW,
    TRIDENT,
    CROSSBOW,
    SPYGLASS,
    TOOT_HORN,
    BRUSH,
    BUNDLE,
    SPEAR;

    public static final NetworkBuffer.Type<ItemAnimation> NETWORK_TYPE = NetworkBuffer.Enum(ItemAnimation.class);
    public static final Codec<ItemAnimation> CODEC = Codec.Enum(ItemAnimation.class);
}
