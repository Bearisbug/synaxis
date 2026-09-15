package io.github.bearisbug.synaxis.crypto;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;

import java.util.UUID;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.UUID;

public record ChatSession(UUID sessionId, PlayerPublicKey publicKey) {
    public static final NetworkBuffer.Type<ChatSession> SERIALIZER = NetworkBufferTemplate.template(
            UUID, ChatSession::sessionId,
            PlayerPublicKey.SERIALIZER, ChatSession::publicKey,
            ChatSession::new
    );
}
