package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.potion.PotionEffect;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record RemoveEntityEffectPacket(int entityId, PotionEffect potionEffect) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<RemoveEntityEffectPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, RemoveEntityEffectPacket::entityId,
            PotionEffect.NETWORK_TYPE, RemoveEntityEffectPacket::potionEffect,
            RemoveEntityEffectPacket::new
    );
}
