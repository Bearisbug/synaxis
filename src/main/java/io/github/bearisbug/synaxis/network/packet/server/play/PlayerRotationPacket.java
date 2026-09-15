package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

public record PlayerRotationPacket(
        float yaw,
        boolean relativeYaw,
        float pitch,
        boolean relativePitch
) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<PlayerRotationPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.FLOAT, PlayerRotationPacket::yaw,
            NetworkBuffer.BOOLEAN, PlayerRotationPacket::relativeYaw,
            NetworkBuffer.FLOAT, PlayerRotationPacket::pitch,
            NetworkBuffer.BOOLEAN, PlayerRotationPacket::relativePitch,
            PlayerRotationPacket::new);
}
