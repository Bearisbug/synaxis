package io.github.bearisbug.synaxis.world.attribute;

import io.github.bearisbug.synaxis.codec.Codec;

non-sealed interface BooleanModifier extends EnvironmentAttribute.Modifier<Boolean, Boolean> {
    // One instance per operator: the modifier codec of a type finds the operator of a modifier by its instance
    BooleanModifier AND = new BooleanModifier() {};
    BooleanModifier NAND = new BooleanModifier() {};
    BooleanModifier OR = new BooleanModifier() {};
    BooleanModifier NOR = new BooleanModifier() {};
    BooleanModifier XOR = new BooleanModifier() {};
    BooleanModifier XNOR = new BooleanModifier() {};

    @java.lang.Override
    default Codec<java.lang.Boolean> argumentCodec() {
        return Codec.BOOLEAN;
    }
}
