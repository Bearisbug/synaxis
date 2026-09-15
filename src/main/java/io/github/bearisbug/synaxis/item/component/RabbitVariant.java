package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

public enum RabbitVariant {
    BROWN("brown"),
    WHITE("white"),
    BLACK("black"),
    BLACK_AND_WHITE("white_splotched"),
    GOLD("gold"),
    SALT_AND_PEPPER("salt"),
    KILLER_BUNNY("evil");

    // Three constant names differ from the vanilla registry, so the name cannot be used as the key
    private final String key;

    RabbitVariant(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static final NetworkBuffer.Type<RabbitVariant> NETWORK_TYPE = NetworkBuffer.Enum(RabbitVariant.class);
    public static final Codec<RabbitVariant> CODEC = Codec.STRING.transform(RabbitVariant::fromKey, RabbitVariant::key);

    private final static RabbitVariant[] VALUES = values();

    private static RabbitVariant fromKey(String key) {
        for (RabbitVariant variant : VALUES) {
            if (variant.key.equals(key)) return variant;
        }
        throw new IllegalArgumentException("Unknown rabbit variant: " + key);
    }
}
