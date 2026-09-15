package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BLOCK_POSITION;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ClientGenerateStructurePacket(Point blockPosition,
                                            int level, boolean keepJigsaws) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientGenerateStructurePacket> SERIALIZER = NetworkBufferTemplate.template(
            BLOCK_POSITION, ClientGenerateStructurePacket::blockPosition,
            VAR_INT, ClientGenerateStructurePacket::level,
            BOOLEAN, ClientGenerateStructurePacket::keepJigsaws,
            ClientGenerateStructurePacket::new);
}
