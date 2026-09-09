package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

public record ClientAttackPacket(int targetId) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientAttackPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.VAR_INT, ClientAttackPacket::targetId,
            ClientAttackPacket::new
    );
}
