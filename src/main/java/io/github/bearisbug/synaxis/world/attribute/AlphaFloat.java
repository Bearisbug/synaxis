package io.github.bearisbug.synaxis.world.attribute;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.utils.Either;

import java.util.function.Function;

public record AlphaFloat(float value, float alpha) {
    private static final StructCodec<AlphaFloat> STRUCT_CODEC = StructCodec.struct(
            "value", Codec.FLOAT, AlphaFloat::value,
            "alpha", Codec.FLOAT.optional(1f), AlphaFloat::alpha,
            AlphaFloat::new);
    public static final Codec<AlphaFloat> CODEC = Codec.Either(Codec.FLOAT, STRUCT_CODEC).transform(
            either -> either.unify(AlphaFloat::new, Function.identity()),
            alphaFloat -> alphaFloat.alpha() == 1f ? Either.left(alphaFloat.value()) : Either.right(alphaFloat)
    );

    public AlphaFloat(float value) {
        this(value, 1f);
    }
}
