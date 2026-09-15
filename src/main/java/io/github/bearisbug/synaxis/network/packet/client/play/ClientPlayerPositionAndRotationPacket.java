package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.POS;
import static io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket.FLAG_HORIZONTAL_COLLISION;
import static io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket.FLAG_ON_GROUND;

public record ClientPlayerPositionAndRotationPacket(Pos position, byte flags) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPlayerPositionAndRotationPacket> SERIALIZER = NetworkBufferTemplate.template(
            POS, ClientPlayerPositionAndRotationPacket::position,
            BYTE, ClientPlayerPositionAndRotationPacket::flags,
            ClientPlayerPositionAndRotationPacket::new);

    public ClientPlayerPositionAndRotationPacket(Pos position, boolean onGround, boolean horizontalCollision) {
        this(position, (byte) ((onGround ? FLAG_ON_GROUND : 0) |
                (byte) (horizontalCollision ? FLAG_HORIZONTAL_COLLISION : 0)));
    }

    public boolean onGround() {
        return (flags & FLAG_ON_GROUND) != 0;
    }

    public boolean horizontalCollision() {
        return (flags & FLAG_HORIZONTAL_COLLISION) != 0;
    }
}
