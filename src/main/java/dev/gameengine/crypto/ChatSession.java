package dev.gameengine.crypto;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;

import java.util.UUID;

import static dev.gameengine.network.NetworkBuffer.UUID;

public record ChatSession(UUID sessionId, PlayerPublicKey publicKey) {
    public static final NetworkBuffer.Type<ChatSession> SERIALIZER = NetworkBufferTemplate.template(
            UUID, ChatSession::sessionId,
            PlayerPublicKey.SERIALIZER, ChatSession::publicKey,
            ChatSession::new
    );
}
