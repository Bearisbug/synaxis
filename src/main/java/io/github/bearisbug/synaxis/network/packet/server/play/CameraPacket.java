package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record CameraPacket(int cameraId) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<CameraPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, CameraPacket::cameraId,
            CameraPacket::new);
}
