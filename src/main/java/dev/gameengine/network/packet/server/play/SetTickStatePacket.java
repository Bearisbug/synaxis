package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;
import static dev.gameengine.network.NetworkBuffer.FLOAT;

public record SetTickStatePacket(float tickRate, boolean isFrozen) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<SetTickStatePacket> SERIALIZER = NetworkBufferTemplate.template(
            FLOAT, SetTickStatePacket::tickRate,
            BOOLEAN, SetTickStatePacket::isFrozen,
            SetTickStatePacket::new);
}
