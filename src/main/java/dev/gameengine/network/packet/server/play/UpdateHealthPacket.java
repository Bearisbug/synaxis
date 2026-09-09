package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.FLOAT;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record UpdateHealthPacket(float health, int food, float foodSaturation) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<UpdateHealthPacket> SERIALIZER = NetworkBufferTemplate.template(
            FLOAT, UpdateHealthPacket::health,
            VAR_INT, UpdateHealthPacket::food,
            FLOAT, UpdateHealthPacket::foodSaturation,
            UpdateHealthPacket::new);
}
