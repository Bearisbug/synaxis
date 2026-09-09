package dev.gameengine.network.packet.client.status;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.BYTE;

public record LegacyServerListPingPacket(byte payload) implements ClientPacket.Status {
    public static final NetworkBuffer.Type<LegacyServerListPingPacket> SERIALIZER = NetworkBufferTemplate.template(
            BYTE, LegacyServerListPingPacket::payload,
            LegacyServerListPingPacket::new);
}
