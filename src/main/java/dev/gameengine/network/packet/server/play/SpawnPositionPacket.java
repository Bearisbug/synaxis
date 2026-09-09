package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.network.packet.server.play.data.WorldPos;

import static dev.gameengine.network.NetworkBuffer.FLOAT;

public record SpawnPositionPacket(WorldPos worldPos, float yaw, float pitch) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<SpawnPositionPacket> SERIALIZER = NetworkBufferTemplate.template(
            WorldPos.NETWORK_TYPE, SpawnPositionPacket::worldPos,
            FLOAT, SpawnPositionPacket::yaw,
            FLOAT, SpawnPositionPacket::pitch,
            SpawnPositionPacket::new);
}
