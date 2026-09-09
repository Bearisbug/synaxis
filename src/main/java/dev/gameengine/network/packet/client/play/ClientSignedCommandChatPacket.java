package dev.gameengine.network.packet.client.play;

import dev.gameengine.crypto.ArgumentSignatures;
import dev.gameengine.crypto.LastSeenMessages;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.utils.validate.Check;

import static dev.gameengine.network.NetworkBuffer.BYTE;
import static dev.gameengine.network.NetworkBuffer.LONG;
import static dev.gameengine.network.NetworkBuffer.STRING;

public record ClientSignedCommandChatPacket(String message, long timestamp,
                                            long salt, ArgumentSignatures signatures,
                                            LastSeenMessages.Update lastSeenMessages,
                                            byte checksum) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientSignedCommandChatPacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING, ClientSignedCommandChatPacket::message,
            LONG, ClientSignedCommandChatPacket::timestamp,
            LONG, ClientSignedCommandChatPacket::salt,
            ArgumentSignatures.SERIALIZER, ClientSignedCommandChatPacket::signatures,
            LastSeenMessages.Update.SERIALIZER, ClientSignedCommandChatPacket::lastSeenMessages,
            BYTE, ClientSignedCommandChatPacket::checksum,
            ClientSignedCommandChatPacket::new
    );

    public ClientSignedCommandChatPacket {
        Check.argCondition(message.length() > 256, "Message length cannot be greater than 256");
    }
}
