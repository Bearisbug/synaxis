package dev.gameengine.network.packet.server.play;

import dev.gameengine.coordinate.Point;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.BLOCK_POSITION;
import static dev.gameengine.network.NetworkBuffer.BOOLEAN;
import static dev.gameengine.network.NetworkBuffer.INT;

public record WorldEventPacket(int effectId, Point position, int data,
                               boolean disableRelativeVolume) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<WorldEventPacket> SERIALIZER = NetworkBufferTemplate.template(
            INT, WorldEventPacket::effectId,
            BLOCK_POSITION, WorldEventPacket::position,
            INT, WorldEventPacket::data,
            BOOLEAN, WorldEventPacket::disableRelativeVolume,
            WorldEventPacket::new);
}
