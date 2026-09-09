package dev.gameengine.item.component;

import dev.gameengine.codec.Codec;
import dev.gameengine.codec.StructCodec;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;

public record SwingAnimation(Type type, int duration) {
    public static final SwingAnimation DEFAULT = new SwingAnimation(Type.WHACK, 6);

    public static final NetworkBuffer.Type<SwingAnimation> NETWORK_TYPE = NetworkBufferTemplate.template(
            Type.NETWORK_TYPE, SwingAnimation::type,
            NetworkBuffer.VAR_INT, SwingAnimation::duration,
            SwingAnimation::new);
    public static final Codec<SwingAnimation> CODEC = StructCodec.struct(
            "type", Type.CODEC.optional(Type.WHACK), SwingAnimation::type,
            "duration", Codec.INT.optional(6), SwingAnimation::duration,
            SwingAnimation::new);

    public enum Type {
        NONE,
        WHACK,
        STAB;

        public static final NetworkBuffer.Type<Type> NETWORK_TYPE = NetworkBuffer.Enum(Type.class);
        public static final Codec<Type> CODEC = Codec.Enum(Type.class);
    }
}
