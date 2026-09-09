package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.potion.PotionEffect;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record RemoveEntityEffectPacket(int entityId, PotionEffect potionEffect) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<RemoveEntityEffectPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, RemoveEntityEffectPacket::entityId,
            PotionEffect.NETWORK_TYPE, RemoveEntityEffectPacket::potionEffect,
            RemoveEntityEffectPacket::new
    );
}
