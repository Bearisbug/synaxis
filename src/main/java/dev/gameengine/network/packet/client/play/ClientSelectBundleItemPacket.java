package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientSelectBundleItemPacket(int slot, int selectedIndex) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientSelectBundleItemPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientSelectBundleItemPacket::slot,
            VAR_INT, ClientSelectBundleItemPacket::selectedIndex,
            ClientSelectBundleItemPacket::new);
}
