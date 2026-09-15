package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.RelativeFlags;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import org.intellij.lang.annotations.MagicConstant;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.FLOAT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.INT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VECTOR3D;

public record PlayerPositionAndLookPacket(
        int teleportId, Point position, Point delta,
        float yaw, float pitch,
        @MagicConstant(flagsFromClass = RelativeFlags.class) int flags
) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<PlayerPositionAndLookPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, PlayerPositionAndLookPacket::teleportId,
            VECTOR3D, PlayerPositionAndLookPacket::position,
            VECTOR3D, PlayerPositionAndLookPacket::delta,
            FLOAT, PlayerPositionAndLookPacket::yaw,
            FLOAT, PlayerPositionAndLookPacket::pitch,
            INT, PlayerPositionAndLookPacket::flags,
            PlayerPositionAndLookPacket::new);
}