package dev.gameengine.network.packet.server.common;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.STRING;

public record CookieRequestPacket(String key) implements
        ServerPacket.Login, ServerPacket.Configuration, ServerPacket.Play {
    public static final NetworkBuffer.Type<CookieRequestPacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING, CookieRequestPacket::key,
            CookieRequestPacket::new);
}
