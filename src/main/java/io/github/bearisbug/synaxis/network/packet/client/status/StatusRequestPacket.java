package io.github.bearisbug.synaxis.network.packet.client.status;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

public record StatusRequestPacket() implements ClientPacket.Status {
    public static final NetworkBuffer.Type<StatusRequestPacket> SERIALIZER = NetworkBufferTemplate.template(new StatusRequestPacket());
}
