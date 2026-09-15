package io.github.bearisbug.synaxis.network.packet.server.configuration;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

public record CodeOfConductPacket(
        String codeOfConduct
) implements ServerPacket.Configuration {
    public static final NetworkBuffer.Type<CodeOfConductPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.STRING, CodeOfConductPacket::codeOfConduct,
            CodeOfConductPacket::new);
}
