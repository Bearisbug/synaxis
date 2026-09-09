package dev.gameengine.network.packet.client.common;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.network.player.ClientSettings;

public record ClientSettingsPacket(ClientSettings settings) implements ClientPacket.Configuration, ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientSettingsPacket> SERIALIZER = NetworkBufferTemplate.template(
            ClientSettings.NETWORK_TYPE, ClientSettingsPacket::settings,
            ClientSettingsPacket::new);
}
