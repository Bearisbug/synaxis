package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;
import org.jetbrains.annotations.Nullable;

/**
 * Sent from the client when in spectator mode, and uses the primary click.
 *
 * @param targetId the target entity id clicked, null if not present.
 */
public record ClientSpectatorActionPacket(@Nullable Integer targetId) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientSpectatorActionPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.OPTIONAL_VAR_INT, ClientSpectatorActionPacket::targetId,
            ClientSpectatorActionPacket::new
    );
}