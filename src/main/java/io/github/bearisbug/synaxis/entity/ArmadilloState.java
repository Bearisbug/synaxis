package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.network.NetworkBuffer;

public enum ArmadilloState {
    IDLE,
    ROLLING,
    SCARED,
    UNROLLING;

    public static final NetworkBuffer.Type<ArmadilloState> NETWORK_TYPE = NetworkBuffer.Enum(ArmadilloState.class);
}
