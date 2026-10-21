package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.utils.validate.Check;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;

public record ClientNameItemPacket(String itemName) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientNameItemPacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING, ClientNameItemPacket::itemName,
            ClientNameItemPacket::new);

    public ClientNameItemPacket {
        Check.argCondition(itemName.length() > Short.MAX_VALUE, "Item name cannot be longer than Short.MAX_VALUE");
    }
}
