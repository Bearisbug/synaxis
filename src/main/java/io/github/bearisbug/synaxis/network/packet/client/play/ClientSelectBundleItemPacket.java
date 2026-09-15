package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ClientSelectBundleItemPacket(int slot, int selectedIndex) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientSelectBundleItemPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientSelectBundleItemPacket::slot,
            VAR_INT, ClientSelectBundleItemPacket::selectedIndex,
            ClientSelectBundleItemPacket::new);
}
