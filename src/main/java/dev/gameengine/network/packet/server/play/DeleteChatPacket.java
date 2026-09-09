package dev.gameengine.network.packet.server.play;

import dev.gameengine.crypto.MessageSignature;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

public record DeleteChatPacket(MessageSignature signature) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<DeleteChatPacket> SERIALIZER = NetworkBufferTemplate.template(
            MessageSignature.SERIALIZER, DeleteChatPacket::signature,
            DeleteChatPacket::new
    );
}
