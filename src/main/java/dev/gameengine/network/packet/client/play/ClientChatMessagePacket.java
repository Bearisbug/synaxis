package dev.gameengine.network.packet.client.play;

import dev.gameengine.crypto.MessageSignature;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.utils.validate.Check;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;

import static dev.gameengine.network.NetworkBuffer.BYTE;
import static dev.gameengine.network.NetworkBuffer.FixedBitSet;
import static dev.gameengine.network.NetworkBuffer.LONG;
import static dev.gameengine.network.NetworkBuffer.STRING;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientChatMessagePacket(String message, long timestamp,
                                      long salt, @Nullable MessageSignature signature,
                                      int ackOffset, BitSet ackList, byte checksum) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientChatMessagePacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING, ClientChatMessagePacket::message,
            LONG, ClientChatMessagePacket::timestamp,
            LONG, ClientChatMessagePacket::salt,
            MessageSignature.SERIALIZER.optional(), ClientChatMessagePacket::signature,
            VAR_INT, ClientChatMessagePacket::ackOffset,
            FixedBitSet(20), ClientChatMessagePacket::ackList,
            BYTE, ClientChatMessagePacket::checksum,
            ClientChatMessagePacket::new
    );

    public ClientChatMessagePacket {
        Check.argCondition(message.length() > 256, "Message length cannot be greater than 256");
        ackList = (BitSet) ackList.clone();
    }
}
