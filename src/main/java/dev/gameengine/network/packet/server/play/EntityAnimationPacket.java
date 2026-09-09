package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record EntityAnimationPacket(int entityId, Animation animation) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<EntityAnimationPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, EntityAnimationPacket::entityId,
            NetworkBuffer.Enum(Animation.class), EntityAnimationPacket::animation,
            EntityAnimationPacket::new
    );

    public enum Animation {
        SWING_MAIN_ARM,
        TAKE_DAMAGE,
        LEAVE_BED,
        SWING_OFF_HAND,
        CRITICAL_EFFECT,
        MAGICAL_CRITICAL_EFFECT
    }
}
