package dev.gameengine.network.packet.server;

import dev.gameengine.network.NetworkBuffer;
import org.jetbrains.annotations.ApiStatus;

/**
 * Represents a buffer to directly write to the network.
 * <p>
 * May contain multiple packets.
 */
@ApiStatus.Internal
public record BufferedPacket(NetworkBuffer buffer,
                             long index, long length) implements SendablePacket {
    public BufferedPacket {
        buffer = buffer.readOnly();
    }
}
