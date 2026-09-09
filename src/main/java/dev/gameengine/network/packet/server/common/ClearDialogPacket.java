package dev.gameengine.network.packet.server.common;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

public record ClearDialogPacket() implements ServerPacket.Configuration, ServerPacket.Play {
    public static final NetworkBuffer.Type<ClearDialogPacket> SERIALIZER = NetworkBufferTemplate.template(new ClearDialogPacket());
}
