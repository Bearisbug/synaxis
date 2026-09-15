package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
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