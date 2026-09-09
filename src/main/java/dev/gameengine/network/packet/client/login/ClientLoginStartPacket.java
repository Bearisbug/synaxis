package dev.gameengine.network.packet.client.login;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import java.util.UUID;

import static dev.gameengine.network.NetworkBuffer.STRING;
import static dev.gameengine.network.NetworkBuffer.UUID;

public record ClientLoginStartPacket(String username,
                                     UUID profileId) implements ClientPacket.Login {
    public static final NetworkBuffer.Type<ClientLoginStartPacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING, ClientLoginStartPacket::username,
            UUID, ClientLoginStartPacket::profileId,
            ClientLoginStartPacket::new);
}
