package io.github.bearisbug.synaxis.item.component;

import net.kyori.adventure.util.RGBLike;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.color.Color;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;

import java.util.List;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;

public record FireworkExplosion(
        Shape shape,
        List<RGBLike> colors,
        List<RGBLike> fadeColors,
        boolean hasTrail,
        boolean hasTwinkle
) {

    public enum Shape {
        SMALL_BALL,
        LARGE_BALL,
        STAR,
        CREEPER,
        BURST
    }

    public static final NetworkBuffer.Type<FireworkExplosion> NETWORK_TYPE = NetworkBufferTemplate.template(
            NetworkBuffer.Enum(Shape.class), FireworkExplosion::shape,
            Color.NETWORK_TYPE.list(Short.MAX_VALUE, Integer.MAX_VALUE), FireworkExplosion::colors,
            Color.NETWORK_TYPE.list(Short.MAX_VALUE, Integer.MAX_VALUE), FireworkExplosion::fadeColors,
            BOOLEAN, FireworkExplosion::hasTrail,
            BOOLEAN, FireworkExplosion::hasTwinkle,
            FireworkExplosion::new);
    public static final Codec<FireworkExplosion> CODEC = StructCodec.struct(
            "shape", Codec.Enum(Shape.class), FireworkExplosion::shape,
            "colors", Color.CODEC.list().optional(List.of()), FireworkExplosion::colors,
            "fade_colors", Color.CODEC.list().optional(List.of()), FireworkExplosion::fadeColors,
            "has_trail", Codec.BOOLEAN.optional(false), FireworkExplosion::hasTrail,
            "has_twinkle", Codec.BOOLEAN.optional(false), FireworkExplosion::hasTwinkle,
            FireworkExplosion::new);

    public FireworkExplosion {
        colors = List.copyOf(colors);
        fadeColors = List.copyOf(fadeColors);
    }
}
