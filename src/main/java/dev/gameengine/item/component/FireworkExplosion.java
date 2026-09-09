package dev.gameengine.item.component;

import net.kyori.adventure.util.RGBLike;
import dev.gameengine.codec.Codec;
import dev.gameengine.codec.StructCodec;
import dev.gameengine.color.Color;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;

import java.util.List;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;

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
            Color.NETWORK_TYPE.list(Short.MAX_VALUE), FireworkExplosion::colors,
            Color.NETWORK_TYPE.list(Short.MAX_VALUE), FireworkExplosion::fadeColors,
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
