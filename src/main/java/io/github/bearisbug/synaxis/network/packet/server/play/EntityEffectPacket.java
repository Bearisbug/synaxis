package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.potion.Potion;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record EntityEffectPacket(int entityId, Potion potion) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<EntityEffectPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, EntityEffectPacket::entityId,
            Potion.NETWORK_TYPE, EntityEffectPacket::potion,
            EntityEffectPacket::new
    );
}
