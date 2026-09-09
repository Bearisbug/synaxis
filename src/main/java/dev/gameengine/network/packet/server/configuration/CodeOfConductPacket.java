package dev.gameengine.network.packet.server.configuration;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

public record CodeOfConductPacket(
        String codeOfConduct
) implements ServerPacket.Configuration {
    public static final NetworkBuffer.Type<CodeOfConductPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.STRING, CodeOfConductPacket::codeOfConduct,
            CodeOfConductPacket::new);
}
