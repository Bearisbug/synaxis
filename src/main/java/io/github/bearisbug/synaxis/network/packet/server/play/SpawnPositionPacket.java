package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.WorldPos;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.FLOAT;

public record SpawnPositionPacket(WorldPos worldPos, float yaw, float pitch) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<SpawnPositionPacket> SERIALIZER = NetworkBufferTemplate.template(
            WorldPos.NETWORK_TYPE, SpawnPositionPacket::worldPos,
            FLOAT, SpawnPositionPacket::yaw,
            FLOAT, SpawnPositionPacket::pitch,
            SpawnPositionPacket::new);
}
