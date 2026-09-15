package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket.FLAG_HORIZONTAL_COLLISION;
import static io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket.FLAG_ON_GROUND;

public record ClientPlayerPositionStatusPacket(byte flags) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPlayerPositionStatusPacket> SERIALIZER = NetworkBufferTemplate.template(
            BYTE, ClientPlayerPositionStatusPacket::flags,
            ClientPlayerPositionStatusPacket::new);

    public ClientPlayerPositionStatusPacket(boolean onGround, boolean horizontalCollision) {
        this((byte) ((onGround ? FLAG_ON_GROUND : 0) |
                (byte) (horizontalCollision ? FLAG_HORIZONTAL_COLLISION : 0)));
    }

    public boolean onGround() {
        return (flags & FLAG_ON_GROUND) != 0;
    }

    public boolean horizontalCollision() {
        return (flags & FLAG_HORIZONTAL_COLLISION) != 0;
    }

}
