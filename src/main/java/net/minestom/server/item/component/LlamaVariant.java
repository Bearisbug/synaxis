package net.minestom.server.item.component;

import net.minestom.server.codec.Codec;
import net.minestom.server.network.NetworkBuffer;

public enum LlamaVariant {
    CREAMY,
    WHITE,
    BROWN,
    GRAY;

    public static final NetworkBuffer.Type<LlamaVariant> NETWORK_TYPE = NetworkBuffer.Enum(LlamaVariant.class);
    public static final Codec<LlamaVariant> CODEC = Codec.Enum(LlamaVariant.class);
}
