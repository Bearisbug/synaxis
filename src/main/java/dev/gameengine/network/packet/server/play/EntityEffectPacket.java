package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.potion.Potion;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record EntityEffectPacket(int entityId, Potion potion) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<EntityEffectPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, EntityEffectPacket::entityId,
            Potion.NETWORK_TYPE, EntityEffectPacket::potion,
            EntityEffectPacket::new
    );
}
