package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.FLOAT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record UpdateHealthPacket(float health, int food, float foodSaturation) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<UpdateHealthPacket> SERIALIZER = NetworkBufferTemplate.template(
            FLOAT, UpdateHealthPacket::health,
            VAR_INT, UpdateHealthPacket::food,
            FLOAT, UpdateHealthPacket::foodSaturation,
            UpdateHealthPacket::new);
}
