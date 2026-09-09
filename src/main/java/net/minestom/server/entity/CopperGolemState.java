package net.minestom.server.entity;

import net.minestom.server.codec.Codec;
import net.minestom.server.network.NetworkBuffer;

public enum CopperGolemState {
    IDLE,
    GETTING_ITEM,
    GETTING_NO_ITEM,
    DROPPING_ITEM,
    DROPPING_NO_ITEM;

    public static final NetworkBuffer.Type<CopperGolemState> NETWORK_TYPE = NetworkBuffer.Enum(CopperGolemState.class);
    public static final Codec<CopperGolemState> CODEC = Codec.Enum(CopperGolemState.class);
}
