package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BLOCK_POSITION;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;

public record ClientPickItemFromBlockPacket(Point pos, boolean includeData) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPickItemFromBlockPacket> SERIALIZER = NetworkBufferTemplate.template(
            BLOCK_POSITION, ClientPickItemFromBlockPacket::pos,
            BOOLEAN, ClientPickItemFromBlockPacket::includeData,
            ClientPickItemFromBlockPacket::new);
}
