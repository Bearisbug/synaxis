package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import java.util.Objects;
import java.util.UUID;

public record ClientTeleportToEntityPacket(UUID target) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientTeleportToEntityPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.UUID, ClientTeleportToEntityPacket::target,
            ClientTeleportToEntityPacket::new
    );

    public ClientTeleportToEntityPacket {
        Objects.requireNonNull(target, "target");
    }
}
