package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

// This is the packet sent when you toggle a slot in a crafter UI
public record ClientWindowSlotStatePacket(int slot, int windowId, boolean newState) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientWindowSlotStatePacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientWindowSlotStatePacket::slot,
            VAR_INT, ClientWindowSlotStatePacket::windowId,
            BOOLEAN, ClientWindowSlotStatePacket::newState,
            ClientWindowSlotStatePacket::new);
}
