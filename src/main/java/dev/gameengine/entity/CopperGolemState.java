package dev.gameengine.entity;

import dev.gameengine.codec.Codec;
import dev.gameengine.network.NetworkBuffer;

public enum CopperGolemState {
    IDLE,
    GETTING_ITEM,
    GETTING_NO_ITEM,
    DROPPING_ITEM,
    DROPPING_NO_ITEM;

    public static final NetworkBuffer.Type<CopperGolemState> NETWORK_TYPE = NetworkBuffer.Enum(CopperGolemState.class);
    public static final Codec<CopperGolemState> CODEC = Codec.Enum(CopperGolemState.class);
}
