package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.utils.validate.Check;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

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
