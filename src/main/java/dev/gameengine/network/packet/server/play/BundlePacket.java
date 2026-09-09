package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

public record BundlePacket() implements ServerPacket.Play {
    public static final NetworkBuffer.Type<BundlePacket> SERIALIZER = NetworkBufferTemplate.template(new BundlePacket());
}
