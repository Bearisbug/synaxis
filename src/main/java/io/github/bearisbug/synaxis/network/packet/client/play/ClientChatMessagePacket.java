package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.crypto.MessageSignature;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.FixedBitSet;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.LONG;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

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
