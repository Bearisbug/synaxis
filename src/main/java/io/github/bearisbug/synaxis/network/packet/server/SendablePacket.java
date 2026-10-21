package io.github.bearisbug.synaxis.network.packet.server;

import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a packet that can be sent to a {@link PlayerConnection}.
 */
public sealed interface SendablePacket
        permits BufferedPacket, CachedPacket, FramedPacket, ServerPacket {

    /**
     * Returns the packet object behind a sendable packet.
     *
     * @param state  unused, kept so that the callers do not change
     * @param packet the sendable packet
     * @return the packet object, or {@code null} for a {@link BufferedPacket}, which carries only encoded
     * bytes, so callers must handle {@code null}; a {@link CachedPacket} holding no cached value has its
     * supplier build one on the spot, which is not cached
     */
    static @Nullable ServerPacket extractServerPacket(ConnectionState state, SendablePacket packet) {
        return switch (packet) {
            case ServerPacket serverPacket -> serverPacket;
            case CachedPacket cachedPacket -> cachedPacket.packet();
            case FramedPacket framedPacket -> framedPacket.packet();
            case BufferedPacket _ -> null;
        };
    }
}
