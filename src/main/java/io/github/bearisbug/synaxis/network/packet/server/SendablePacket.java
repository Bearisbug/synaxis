package io.github.bearisbug.synaxis.network.packet.server;

import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a packet that can be sent to a {@link PlayerConnection}.
 */
public sealed interface SendablePacket
        permits BufferedPacket, CachedPacket, FramedPacket, ServerPacket {

    static @Nullable ServerPacket extractServerPacket(ConnectionState state, SendablePacket packet) {
        return switch (packet) {
            case ServerPacket serverPacket -> serverPacket;
            case CachedPacket cachedPacket -> cachedPacket.packet();
            case FramedPacket framedPacket -> framedPacket.packet();
            case BufferedPacket _ -> null;
        };
    }
}
