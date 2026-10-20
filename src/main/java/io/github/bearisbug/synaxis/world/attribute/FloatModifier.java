package io.github.bearisbug.synaxis.world.attribute;

import io.github.bearisbug.synaxis.codec.Codec;

non-sealed interface FloatModifier<Arg> extends EnvironmentAttribute.Modifier<Float, Arg> {
    FloatModifier<AlphaFloat> ALPHA_BLEND = new FloatModifier<>() {
        @java.lang.Override
        public Codec<AlphaFloat> argumentCodec() {
            return AlphaFloat.CODEC;
        }
    };
    // One instance per operator: the modifier codec of a type finds the operator of a modifier by its instance
    ToFloat ADD = new ToFloat() {};
    ToFloat SUBTRACT = new ToFloat() {};
    ToFloat MULTIPLY = new ToFloat() {};
    ToFloat MINIMUM = new ToFloat() {};
    ToFloat MAXIMUM = new ToFloat() {};

    interface ToFloat extends FloatModifier<java.lang.Float> {
        @java.lang.Override
        default Codec<java.lang.Float> argumentCodec() {
            return Codec.FLOAT;
        }
    }
}
