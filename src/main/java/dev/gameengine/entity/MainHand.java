package dev.gameengine.entity;

import dev.gameengine.network.NetworkBuffer;

/**
 * Represents where is located the main hand of the player (can be changed in Minecraft option).
 */
public enum MainHand {
    LEFT,
    RIGHT;

    public static final NetworkBuffer.Type<MainHand> NETWORK_TYPE = NetworkBuffer.Enum(
        MainHand.class);
}
