package io.github.bearisbug.synaxis.world.attribute;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;

public record BlendToGray(float brightness, float factor) {
    public static final Codec<BlendToGray> CODEC = StructCodec.struct(
            "brightness", Codec.FLOAT, BlendToGray::brightness,
            "factor", Codec.FLOAT, BlendToGray::factor,
            BlendToGray::new);
}
