package io.github.bearisbug.synaxis.network.packet.client.login;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import java.util.UUID;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.UUID;

public record ClientLoginStartPacket(String username,
                                     UUID profileId) implements ClientPacket.Login {
    public static final NetworkBuffer.Type<ClientLoginStartPacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING, ClientLoginStartPacket::username,
            UUID, ClientLoginStartPacket::profileId,
            ClientLoginStartPacket::new);
}
