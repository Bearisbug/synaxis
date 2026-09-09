package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

public record ClientTickEndPacket() implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientTickEndPacket> SERIALIZER =
            NetworkBufferTemplate.template(new ClientTickEndPacket());

}
