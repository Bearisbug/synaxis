package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

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
