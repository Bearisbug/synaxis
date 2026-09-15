package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

public record ClientStatusPacket(Action action) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientStatusPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.Enum(Action.class), ClientStatusPacket::action,
            ClientStatusPacket::new);

    public enum Action {
        PERFORM_RESPAWN,
        REQUEST_STATS,
        REQUEST_GAMERULE_VALUES
    }
}
