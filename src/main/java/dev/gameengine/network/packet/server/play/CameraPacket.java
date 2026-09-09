package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record CameraPacket(int cameraId) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<CameraPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, CameraPacket::cameraId,
            CameraPacket::new);
}
