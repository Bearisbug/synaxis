package io.github.bearisbug.synaxis.network.packet.server;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import org.jetbrains.annotations.ApiStatus;

/**
 * Represents a buffer to directly write to the network.
 * <p>
 * May contain multiple packets.
 *
 * @param sheddable whether a connection over its outbound backlog may drop the packet unsent: only
 *                  entity movement the next position sync replaces is ({@code docs/DESIGN-M3.md}
 *                  {@code ADR-M3-018}), the relative moves and rotations, teleports, head turns and
 *                  position syncs of {@link io.github.bearisbug.synaxis.network.PacketViewableUtils}.
 *                  The head of a living entity comes back with its next position sync, the head of any
 *                  other entity does not. Anything else, such as the chunk packets of {@code .synx}
 *                  handle chunks, is written whatever the backlog
 */
@ApiStatus.Internal
public record BufferedPacket(NetworkBuffer buffer,
                             long index, long length, boolean sheddable) implements SendablePacket {
    public BufferedPacket {
        buffer = buffer.readOnly();
    }
}
