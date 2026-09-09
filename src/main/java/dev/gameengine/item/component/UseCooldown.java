package dev.gameengine.item.component;

import dev.gameengine.codec.Codec;
import dev.gameengine.codec.StructCodec;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
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
