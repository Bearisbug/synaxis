package io.github.bearisbug.synaxis.network.packet.client.login;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

public record ClientLoginAcknowledgedPacket() implements ClientPacket.Login {
    public static final NetworkBuffer.Type<ClientLoginAcknowledgedPacket> SERIALIZER = NetworkBufferTemplate.template(new ClientLoginAcknowledgedPacket());
}
