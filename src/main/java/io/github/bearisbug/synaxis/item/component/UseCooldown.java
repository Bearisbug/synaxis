package io.github.bearisbug.synaxis.item.component;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.utils.ThrowingFunction;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Nullable;

public record UseCooldown(float seconds, @Nullable String cooldownGroup) {
    public static final NetworkBuffer.Type<UseCooldown> NETWORK_TYPE = NetworkBufferTemplate.template(
            NetworkBuffer.FLOAT, UseCooldown::seconds,
            NetworkBuffer.STRING.optional(), UseCooldown::cooldownGroup,
            UseCooldown::new);
    // The transform reports a value the constructor refuses as a decode error rather than throwing it out of decode
    public static final Codec<UseCooldown> CODEC = StructCodec.struct(
            "seconds", Codec.FLOAT, UseCooldown::seconds,
            "cooldown_group", Codec.STRING.optional(), UseCooldown::cooldownGroup,
            UseCooldown::new).transform(ThrowingFunction.identity(), ThrowingFunction.identity());

    // An identifier in 26.2, which a client that cannot parse it drops the connection over
    public UseCooldown {
        Check.argCondition(cooldownGroup != null && !Key.parseable(cooldownGroup),
                "cooldownGroup must be a namespaced id such as synaxis:fire_ball (a-z 0-9 . _ - /), got: {0}", cooldownGroup);
    }
}
