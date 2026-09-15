package io.github.bearisbug.synaxis.network.packet.client.status;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;

public record LegacyServerListPingPacket(byte payload) implements ClientPacket.Status {
    public static final NetworkBuffer.Type<LegacyServerListPingPacket> SERIALIZER = NetworkBufferTemplate.template(
            BYTE, LegacyServerListPingPacket::payload,
            LegacyServerListPingPacket::new);
}
