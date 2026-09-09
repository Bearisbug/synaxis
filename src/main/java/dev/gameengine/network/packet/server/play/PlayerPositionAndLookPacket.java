package dev.gameengine.network.packet.server.play;

import dev.gameengine.coordinate.Point;
import dev.gameengine.coordinate.RelativeFlags;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;
import org.intellij.lang.annotations.MagicConstant;

import static dev.gameengine.network.NetworkBuffer.FLOAT;
import static dev.gameengine.network.NetworkBuffer.INT;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;
import static dev.gameengine.network.NetworkBuffer.VECTOR3D;

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