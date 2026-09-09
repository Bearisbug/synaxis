package dev.gameengine.item.component;

import dev.gameengine.codec.Codec;
import dev.gameengine.codec.StructCodec;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;
import static dev.gameengine.network.NetworkBuffer.FLOAT;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

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
