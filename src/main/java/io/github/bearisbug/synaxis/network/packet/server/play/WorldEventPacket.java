package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BLOCK_POSITION;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.INT;

public record WorldEventPacket(int effectId, Point position, int data,
                               boolean disableRelativeVolume) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<WorldEventPacket> SERIALIZER = NetworkBufferTemplate.template(
            INT, WorldEventPacket::effectId,
            BLOCK_POSITION, WorldEventPacket::position,
            INT, WorldEventPacket::data,
            BOOLEAN, WorldEventPacket::disableRelativeVolume,
            WorldEventPacket::new);
}
