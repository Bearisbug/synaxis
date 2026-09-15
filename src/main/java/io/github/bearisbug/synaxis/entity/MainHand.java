package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.network.NetworkBuffer;

/**
 * Represents where is located the main hand of the player (can be changed in Minecraft option).
 */
public enum MainHand {
    LEFT,
    RIGHT;

    public static final NetworkBuffer.Type<MainHand> NETWORK_TYPE = NetworkBuffer.Enum(
        MainHand.class);
}
