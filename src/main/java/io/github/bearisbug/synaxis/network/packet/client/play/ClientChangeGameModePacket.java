package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

public record ClientChangeGameModePacket(GameMode gameMode) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientChangeGameModePacket> SERIALIZER = NetworkBufferTemplate.template(
            GameMode.NETWORK_TYPE, ClientChangeGameModePacket::gameMode,
            ClientChangeGameModePacket::new);
}
