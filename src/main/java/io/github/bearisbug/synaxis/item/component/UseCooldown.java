package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import org.jetbrains.annotations.Nullable;

public record UseCooldown(float seconds, @Nullable String cooldownGroup) {
    public static final NetworkBuffer.Type<UseCooldown> NETWORK_TYPE = NetworkBufferTemplate.template(
            NetworkBuffer.FLOAT, UseCooldown::seconds,
            NetworkBuffer.STRING.optional(), UseCooldown::cooldownGroup,
            UseCooldown::new);
    public static final Codec<UseCooldown> CODEC = StructCodec.struct(
            "seconds", Codec.FLOAT, UseCooldown::seconds,
            "cooldown_group", Codec.STRING.optional(), UseCooldown::cooldownGroup,
            UseCooldown::new);
}
