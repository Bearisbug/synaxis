package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.BYTE;

public record ClientPlayerAbilitiesPacket(byte flags) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPlayerAbilitiesPacket> SERIALIZER = NetworkBufferTemplate.template(
            BYTE, ClientPlayerAbilitiesPacket::flags,
            ClientPlayerAbilitiesPacket::new);
}
