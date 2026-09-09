package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record UpdateViewPositionPacket(int chunkX, int chunkZ) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<UpdateViewPositionPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, UpdateViewPositionPacket::chunkX,
            VAR_INT, UpdateViewPositionPacket::chunkZ,
            UpdateViewPositionPacket::new);
}
