package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.INT;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record OpenHorseWindowPacket(int windowId, int slotCount, int entityId) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<OpenHorseWindowPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, OpenHorseWindowPacket::windowId,
            VAR_INT, OpenHorseWindowPacket::slotCount,
            INT, OpenHorseWindowPacket::entityId,
            OpenHorseWindowPacket::new);
}
