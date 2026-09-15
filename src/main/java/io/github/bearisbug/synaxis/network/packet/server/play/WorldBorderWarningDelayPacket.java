package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record WorldBorderWarningDelayPacket(int warningTime) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<WorldBorderWarningDelayPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, WorldBorderWarningDelayPacket::warningTime,
            WorldBorderWarningDelayPacket::new);
}
