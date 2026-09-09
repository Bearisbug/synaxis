package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

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
