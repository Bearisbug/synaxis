package io.github.bearisbug.synaxis.item.component;

import net.kyori.adventure.util.RGBLike;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.color.Color;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.potion.CustomPotionEffect;
import io.github.bearisbug.synaxis.potion.PotionType;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record PotionContents(
        @Nullable PotionType potion,
        @Nullable RGBLike customColor,
        List<CustomPotionEffect> customEffects,
        @Nullable String customName
) {
    public static final PotionContents EMPTY = new PotionContents(null, null, List.of(), null);

    public static final NetworkBuffer.Type<PotionContents> NETWORK_TYPE = NetworkBufferTemplate.template(
            PotionType.NETWORK_TYPE.optional(), PotionContents::potion,
            Color.NETWORK_TYPE.optional(), PotionContents::customColor,
            CustomPotionEffect.NETWORK_TYPE.list(Short.MAX_VALUE), PotionContents::customEffects,
            NetworkBuffer.STRING.optional(), PotionContents::customName,
            PotionContents::new);
    private static final Codec<PotionContents> POTION_CODEC = PotionType.CODEC.transform(PotionContents::new, PotionContents::potion);
    public static final Codec<PotionContents> CODEC = StructCodec.struct(
            "potion", PotionType.CODEC.optional(), PotionContents::potion,
            "custom_color", Color.CODEC.optional(), PotionContents::customColor,
            "custom_effects", CustomPotionEffect.CODEC.list().optional(List.of()), PotionContents::customEffects,
            "custom_name", Codec.STRING.optional(), PotionContents::customName,
            PotionContents::new).orElse(POTION_CODEC);

    public PotionContents {
        customEffects = List.copyOf(customEffects);
    }

    public PotionContents(PotionType potion) {
        this(potion, null, List.of(), null);
    }

    public PotionContents(PotionType potion, RGBLike customColor) {
        this(potion, customColor, List.of(), null);
    }

    public PotionContents(List<CustomPotionEffect> customEffects) {
        this(null, null, customEffects, null);
    }

    public PotionContents(CustomPotionEffect customEffect) {
        this(null, null, List.of(customEffect), null);
    }

    public PotionContents(@Nullable PotionType potion, @Nullable RGBLike customColor, List<CustomPotionEffect> customEffects) {
        this(potion, customColor, customEffects, null);
    }

}
