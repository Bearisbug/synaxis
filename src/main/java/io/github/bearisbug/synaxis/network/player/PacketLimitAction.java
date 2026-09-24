package io.github.bearisbug.synaxis.network.player;

/**
 * What the inbound packet limit does with a packet over its limit ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-012}).
 */
public enum PacketLimitAction {
    /** The packet is thrown away before it reaches the player's queue, the connection stays. */
    DROP,
    /** The connection is disconnected with {@code synaxis:protocol-error}. */
    KICK
}
