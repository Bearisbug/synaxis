package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.crypto.MessageSignature;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

public record DeleteChatPacket(MessageSignature signature) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<DeleteChatPacket> SERIALIZER = NetworkBufferTemplate.template(
            MessageSignature.SERIALIZER, DeleteChatPacket::signature,
            DeleteChatPacket::new
    );
}
