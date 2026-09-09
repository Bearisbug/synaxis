package dev.gameengine.network.packet.client.play;

import dev.gameengine.coordinate.Point;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.BLOCK_POSITION;
import static dev.gameengine.network.NetworkBuffer.BOOLEAN;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientGenerateStructurePacket(Point blockPosition,
                                            int level, boolean keepJigsaws) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientGenerateStructurePacket> SERIALIZER = NetworkBufferTemplate.template(
            BLOCK_POSITION, ClientGenerateStructurePacket::blockPosition,
            VAR_INT, ClientGenerateStructurePacket::level,
            BOOLEAN, ClientGenerateStructurePacket::keepJigsaws,
            ClientGenerateStructurePacket::new);
}
