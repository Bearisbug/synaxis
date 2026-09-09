package dev.gameengine.item.component;

import dev.gameengine.codec.Codec;
import dev.gameengine.codec.StructCodec;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;

public record UseEffects(
        boolean canSprint,
        boolean interactVibrations,
        float speedMultiplier
) {
    public static final UseEffects DEFAULT = new UseEffects(false, true, 0.2f);

    public static final NetworkBuffer.Type<UseEffects> NETWORK_TYPE = NetworkBufferTemplate.template(
            NetworkBuffer.BOOLEAN, UseEffects::canSprint,
            NetworkBuffer.BOOLEAN, UseEffects::interactVibrations,
            NetworkBuffer.FLOAT, UseEffects::speedMultiplier,
            UseEffects::new);
    public static final Codec<UseEffects> CODEC = StructCodec.struct(
            "can_sprint", Codec.BOOLEAN.optional(false), UseEffects::canSprint,
            "interact_vibrations", Codec.BOOLEAN.optional(true), UseEffects::interactVibrations,
            "speed_multiplier", Codec.FLOAT.optional(0.2f), UseEffects::speedMultiplier,
            UseEffects::new);
}
