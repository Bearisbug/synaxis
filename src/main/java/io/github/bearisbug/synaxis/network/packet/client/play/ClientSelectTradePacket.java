package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ClientSelectTradePacket(int selectedSlot) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientSelectTradePacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientSelectTradePacket::selectedSlot,
            ClientSelectTradePacket::new);
}
