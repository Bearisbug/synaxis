package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.network.NetworkBuffer;

/**
 * Represents the main or off hand of the player.
 */
public enum PlayerHand {
    MAIN,
    OFF;

    public static final NetworkBuffer.Type<PlayerHand> NETWORK_TYPE = NetworkBuffer.Enum(PlayerHand.class);
}
