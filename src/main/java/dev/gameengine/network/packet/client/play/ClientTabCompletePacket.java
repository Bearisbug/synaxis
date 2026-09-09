package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.utils.validate.Check;

import static dev.gameengine.network.NetworkBuffer.STRING;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientTabCompletePacket(int transactionId, String text) implements ClientPacket.Play {
    public static final int MAX_TEXT_LENGTH = 32500;

    public static final NetworkBuffer.Type<ClientTabCompletePacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientTabCompletePacket::transactionId,
            STRING, ClientTabCompletePacket::text,
            ClientTabCompletePacket::new);

    public ClientTabCompletePacket {
        Check.argCondition(text.length() > MAX_TEXT_LENGTH, "Text length cannot be greater than {0}", MAX_TEXT_LENGTH);
    }
}
