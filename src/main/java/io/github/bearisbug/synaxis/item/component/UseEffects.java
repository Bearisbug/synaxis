package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;

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
