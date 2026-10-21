package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.List;
import java.util.UUID;

public record PlayerInfoRemovePacket(List<UUID> uuids) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<PlayerInfoRemovePacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.UUID.list(), PlayerInfoRemovePacket::uuids,
            PlayerInfoRemovePacket::new);

    public PlayerInfoRemovePacket(UUID uuid) {
        this(List.of(uuid));
    }

    public PlayerInfoRemovePacket {
        uuids = List.copyOf(uuids);
    }
}
