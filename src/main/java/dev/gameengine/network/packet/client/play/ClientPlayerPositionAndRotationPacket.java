package dev.gameengine.network.packet.client.play;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.BYTE;
import static dev.gameengine.network.NetworkBuffer.POS;
import static dev.gameengine.network.packet.client.play.ClientPlayerPositionPacket.FLAG_HORIZONTAL_COLLISION;
import static dev.gameengine.network.packet.client.play.ClientPlayerPositionPacket.FLAG_ON_GROUND;

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
