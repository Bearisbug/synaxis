package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record AcknowledgeBlockChangePacket(int sequence) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<AcknowledgeBlockChangePacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, AcknowledgeBlockChangePacket::sequence,
            AcknowledgeBlockChangePacket::new);
}
