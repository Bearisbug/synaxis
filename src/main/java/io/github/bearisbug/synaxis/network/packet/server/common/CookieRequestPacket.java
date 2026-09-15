package io.github.bearisbug.synaxis.network.packet.server.common;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;

public record CookieRequestPacket(String key) implements
        ServerPacket.Login, ServerPacket.Configuration, ServerPacket.Play {
    public static final NetworkBuffer.Type<CookieRequestPacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING, CookieRequestPacket::key,
            CookieRequestPacket::new);
}
