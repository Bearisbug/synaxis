package io.github.bearisbug.synaxis.network.packet.server.login;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;

import java.util.UUID;

public record LoginSuccessPacket(GameProfile gameProfile, UUID sessionId) implements ServerPacket.Login {
    public static final NetworkBuffer.Type<LoginSuccessPacket> SERIALIZER = NetworkBufferTemplate.template(
            GameProfile.SERIALIZER, LoginSuccessPacket::gameProfile,
            NetworkBuffer.UUID, LoginSuccessPacket::sessionId,
            LoginSuccessPacket::new);
}
