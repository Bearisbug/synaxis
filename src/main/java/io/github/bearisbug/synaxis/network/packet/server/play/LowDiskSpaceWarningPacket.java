package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

public record LowDiskSpaceWarningPacket() implements ServerPacket.Play {
    public static final NetworkBuffer.Type<LowDiskSpaceWarningPacket> SERIALIZER = NetworkBufferTemplate.template(new LowDiskSpaceWarningPacket());
}
