package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

public record ClientAttackPacket(int targetId) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientAttackPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.VAR_INT, ClientAttackPacket::targetId,
            ClientAttackPacket::new
    );
}
