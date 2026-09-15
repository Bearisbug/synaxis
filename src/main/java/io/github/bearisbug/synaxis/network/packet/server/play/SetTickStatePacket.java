package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.FLOAT;

public record SetTickStatePacket(float tickRate, boolean isFrozen) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<SetTickStatePacket> SERIALIZER = NetworkBufferTemplate.template(
            FLOAT, SetTickStatePacket::tickRate,
            BOOLEAN, SetTickStatePacket::isFrozen,
            SetTickStatePacket::new);
}
