package io.github.bearisbug.synaxis.network.packet.server.common;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

public record ClearDialogPacket() implements ServerPacket.Configuration, ServerPacket.Play {
    public static final NetworkBuffer.Type<ClearDialogPacket> SERIALIZER = NetworkBufferTemplate.template(new ClearDialogPacket());
}
