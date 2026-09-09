package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.utils.validate.Check;

import static dev.gameengine.network.NetworkBuffer.STRING;

public record ClientNameItemPacket(String itemName) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientNameItemPacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING, ClientNameItemPacket::itemName,
            ClientNameItemPacket::new);

    public ClientNameItemPacket {
        Check.argCondition(itemName.length() > Short.MAX_VALUE, "Item name cannot be longer than Short.MAX_SIZE");
    }
}
