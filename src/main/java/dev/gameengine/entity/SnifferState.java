package dev.gameengine.entity;

import dev.gameengine.network.NetworkBuffer;

public enum SnifferState {
    IDLING,
    FEELING_HAPPY,
    SCENTING,
    SNIFFING,
    SEARCHING,
    DIGGING,
    RISING;

    public static final NetworkBuffer.Type<SnifferState> NETWORK_TYPE = NetworkBuffer.Enum(SnifferState.class);
}
