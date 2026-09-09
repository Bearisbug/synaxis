package dev.gameengine.network.packet.server.common;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record ResourcePackPopPacket(@Nullable UUID id) implements ServerPacket.Configuration, ServerPacket.Play {
    public static final NetworkBuffer.Type<ResourcePackPopPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.UUID.optional(), ResourcePackPopPacket::id,
            ResourcePackPopPacket::new);
}
