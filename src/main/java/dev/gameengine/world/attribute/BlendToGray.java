package dev.gameengine.world.attribute;

import dev.gameengine.codec.Codec;
import dev.gameengine.codec.StructCodec;

public record BlendToGray(float brightness, float factor) {
    public static final Codec<BlendToGray> CODEC = StructCodec.struct(
            "brightness", Codec.FLOAT, BlendToGray::brightness,
            "factor", Codec.FLOAT, BlendToGray::factor,
            BlendToGray::new);
}
