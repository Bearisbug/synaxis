package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.crypto.ArgumentSignatures;
import io.github.bearisbug.synaxis.crypto.LastSeenMessages;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.utils.validate.Check;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.LONG;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;

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
