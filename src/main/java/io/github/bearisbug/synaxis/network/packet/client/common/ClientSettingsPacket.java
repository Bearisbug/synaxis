package io.github.bearisbug.synaxis.network.packet.client.common;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.player.ClientSettings;

public record ClientSettingsPacket(ClientSettings settings) implements ClientPacket.Configuration, ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientSettingsPacket> SERIALIZER = NetworkBufferTemplate.template(
            ClientSettings.NETWORK_TYPE, ClientSettingsPacket::settings,
            ClientSettingsPacket::new);
}
