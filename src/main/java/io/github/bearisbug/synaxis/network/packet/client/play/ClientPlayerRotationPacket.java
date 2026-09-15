package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.FLOAT;
import static io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket.FLAG_HORIZONTAL_COLLISION;
import static io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket.FLAG_ON_GROUND;

public record ClientPlayerRotationPacket(float yaw, float pitch, byte flags) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPlayerRotationPacket> SERIALIZER = NetworkBufferTemplate.template(
            FLOAT, ClientPlayerRotationPacket::yaw,
            FLOAT, ClientPlayerRotationPacket::pitch,
            BYTE, ClientPlayerRotationPacket::flags,
            ClientPlayerRotationPacket::new);

    public ClientPlayerRotationPacket(float yaw, float pitch, boolean onGround, boolean horizontalCollision) {
        this(yaw, pitch, (byte) ((onGround ? FLAG_ON_GROUND : 0) |
                (byte) (horizontalCollision ? FLAG_HORIZONTAL_COLLISION : 0)));
    }

    public boolean onGround() {
        return (flags & FLAG_ON_GROUND) != 0;
    }

    public boolean horizontalCollision() {
        return (flags & FLAG_HORIZONTAL_COLLISION) != 0;
    }
}
