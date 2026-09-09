package dev.gameengine.network.packet.server.login;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.network.player.GameProfile;

import java.util.UUID;

public record LoginSuccessPacket(GameProfile gameProfile, UUID sessionId) implements ServerPacket.Login {
    public static final NetworkBuffer.Type<LoginSuccessPacket> SERIALIZER = NetworkBufferTemplate.template(
            GameProfile.SERIALIZER, LoginSuccessPacket::gameProfile,
            NetworkBuffer.UUID, LoginSuccessPacket::sessionId,
            LoginSuccessPacket::new);
}
