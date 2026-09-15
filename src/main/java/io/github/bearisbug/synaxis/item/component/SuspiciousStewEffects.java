package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;

public record SuspiciousStewEffects(List<Effect> effects) {
    public static final int DEFAULT_DURATION = 160;
    public static final SuspiciousStewEffects EMPTY = new SuspiciousStewEffects(List.of());

    public static final NetworkBuffer.Type<SuspiciousStewEffects> NETWORK_TYPE = Effect.NETWORK_TYPE.list(Short.MAX_VALUE).transform(SuspiciousStewEffects::new, SuspiciousStewEffects::effects);
    public static final Codec<SuspiciousStewEffects> CODEC = Effect.CODEC.list().transform(SuspiciousStewEffects::new, SuspiciousStewEffects::effects);

    public SuspiciousStewEffects {
        effects = List.copyOf(effects);
    }

    public SuspiciousStewEffects(Effect effect) {
        this(List.of(effect));
    }

    public SuspiciousStewEffects with(Effect effect) {
        List<Effect> newEffects = new ArrayList<>(effects);
        newEffects.add(effect);
        return new SuspiciousStewEffects(newEffects);
    }

    public record Effect(PotionEffect id, int durationTicks) {

        public static final NetworkBuffer.Type<Effect> NETWORK_TYPE = NetworkBufferTemplate.template(
                PotionEffect.NETWORK_TYPE, Effect::id,
                NetworkBuffer.VAR_INT, Effect::durationTicks,
                Effect::new
        );
        public static final Codec<Effect> CODEC = StructCodec.struct(
                "id", PotionEffect.CODEC, Effect::id,
                "duration", Codec.INT.optional(DEFAULT_DURATION), Effect::durationTicks,
                Effect::new);

        public Effect(PotionEffect id) {
            this(id, DEFAULT_DURATION);
        }
    }
}
