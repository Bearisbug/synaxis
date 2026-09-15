package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.FLOAT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record Food(int nutrition, float saturationModifier, boolean canAlwaysEat) {

    public static final NetworkBuffer.Type<Food> NETWORK_TYPE = NetworkBufferTemplate.template(
            VAR_INT, Food::nutrition,
            FLOAT, Food::saturationModifier,
            BOOLEAN, Food::canAlwaysEat,
            Food::new);
    public static final Codec<Food> CODEC = StructCodec.struct(
            "nutrition", Codec.INT, Food::nutrition,
            "saturation", Codec.FLOAT, Food::saturationModifier,
            "can_always_eat", Codec.BOOLEAN.optional(false), Food::canAlwaysEat,
            Food::new
    );

}
