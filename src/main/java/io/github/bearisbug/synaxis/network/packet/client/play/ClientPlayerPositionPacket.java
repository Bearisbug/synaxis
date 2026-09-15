package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VECTOR3D;

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
