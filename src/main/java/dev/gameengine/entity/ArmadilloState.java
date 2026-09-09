package dev.gameengine.entity;

import dev.gameengine.network.NetworkBuffer;

public enum ArmadilloState {
    IDLE,
    ROLLING,
    SCARED,
    UNROLLING;

    public static final NetworkBuffer.Type<ArmadilloState> NETWORK_TYPE = NetworkBuffer.Enum(ArmadilloState.class);
}
