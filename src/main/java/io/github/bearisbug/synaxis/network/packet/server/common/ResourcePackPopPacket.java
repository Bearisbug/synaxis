package io.github.bearisbug.synaxis.network.packet.server.common;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record ResourcePackPopPacket(@Nullable UUID id) implements ServerPacket.Configuration, ServerPacket.Play {
    public static final NetworkBuffer.Type<ResourcePackPopPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.UUID.optional(), ResourcePackPopPacket::id,
            ResourcePackPopPacket::new);
}
