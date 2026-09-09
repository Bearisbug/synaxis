package dev.gameengine.network.packet.client.play;

import dev.gameengine.entity.GameMode;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

public record ClientChangeGameModePacket(GameMode gameMode) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientChangeGameModePacket> SERIALIZER = NetworkBufferTemplate.template(
            GameMode.NETWORK_TYPE, ClientChangeGameModePacket::gameMode,
            ClientChangeGameModePacket::new);
}
