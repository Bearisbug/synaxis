package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.FLOAT;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record HitAnimationPacket(int entityId, float yaw) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<HitAnimationPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, HitAnimationPacket::entityId,
            FLOAT, HitAnimationPacket::yaw,
            HitAnimationPacket::new);
}
