package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record WorldBorderWarningReachPacket(int warningBlocks) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<WorldBorderWarningReachPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, WorldBorderWarningReachPacket::warningBlocks,
            WorldBorderWarningReachPacket::new);
}
