package dev.gameengine.item.component;

import dev.gameengine.codec.Codec;
import dev.gameengine.codec.StructCodec;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;

import java.util.List;

public record DeathProtection(List<ConsumeEffect> deathEffects) {
    public static final NetworkBuffer.Type<DeathProtection> NETWORK_TYPE = NetworkBufferTemplate.template(
            ConsumeEffect.NETWORK_TYPE.list(256), DeathProtection::deathEffects,
            DeathProtection::new);
    public static final Codec<DeathProtection> CODEC = StructCodec.struct(
            "death_effects", ConsumeEffect.CODEC.list().optional(List.of()), DeathProtection::deathEffects,
            DeathProtection::new);

    public DeathProtection {
        deathEffects = List.copyOf(deathEffects);
    }
}
