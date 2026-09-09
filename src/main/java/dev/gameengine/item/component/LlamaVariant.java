package dev.gameengine.item.component;

import dev.gameengine.codec.Codec;
import dev.gameengine.network.NetworkBuffer;

public enum LlamaVariant {
    CREAMY,
    WHITE,
    BROWN,
    GRAY;

    public static final NetworkBuffer.Type<LlamaVariant> NETWORK_TYPE = NetworkBuffer.Enum(LlamaVariant.class);
    public static final Codec<LlamaVariant> CODEC = Codec.Enum(LlamaVariant.class);
}
