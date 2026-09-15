package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;

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
