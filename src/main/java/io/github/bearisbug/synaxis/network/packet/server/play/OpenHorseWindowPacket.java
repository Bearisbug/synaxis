package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.INT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record OpenHorseWindowPacket(int windowId, int slotCount, int entityId) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<OpenHorseWindowPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, OpenHorseWindowPacket::windowId,
            VAR_INT, OpenHorseWindowPacket::slotCount,
            INT, OpenHorseWindowPacket::entityId,
            OpenHorseWindowPacket::new);
}
