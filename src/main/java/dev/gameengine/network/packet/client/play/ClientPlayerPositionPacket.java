package dev.gameengine.network.packet.client.play;

import dev.gameengine.coordinate.Point;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.BYTE;
import static dev.gameengine.network.NetworkBuffer.VECTOR3D;

public record ClientPlayerPositionPacket(Point position, byte flags) implements ClientPacket.Play {
    public static final int FLAG_ON_GROUND = 1;
    public static final int FLAG_HORIZONTAL_COLLISION = 1 << 1;

    public static final NetworkBuffer.Type<ClientPlayerPositionPacket> SERIALIZER = NetworkBufferTemplate.template(
            VECTOR3D, ClientPlayerPositionPacket::position,
            BYTE, ClientPlayerPositionPacket::flags,
            ClientPlayerPositionPacket::new);

    public ClientPlayerPositionPacket(Point position, boolean onGround, boolean horizontalCollision) {
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
