package dev.gameengine.network.packet.client.login;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

public record ClientLoginAcknowledgedPacket() implements ClientPacket.Login {
    public static final NetworkBuffer.Type<ClientLoginAcknowledgedPacket> SERIALIZER = NetworkBufferTemplate.template(new ClientLoginAcknowledgedPacket());
}
