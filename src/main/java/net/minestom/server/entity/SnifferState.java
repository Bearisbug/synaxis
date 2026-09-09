package net.minestom.server.entity;

import net.minestom.server.network.NetworkBuffer;

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
