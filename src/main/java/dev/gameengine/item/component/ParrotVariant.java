package dev.gameengine.item.component;

import dev.gameengine.codec.Codec;
import dev.gameengine.network.NetworkBuffer;

public enum ParrotVariant {
    RED_BLUE("red_blue"),
    BLUE("blue"),
    GREEN("green"),
    YELLOW_BLUE("yellow_blue"),
    GREY("gray");

    // GREY is spelled "gray" in the vanilla registry, so the name cannot be used as the key
    private final String key;

    ParrotVariant(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static final NetworkBuffer.Type<ParrotVariant> NETWORK_TYPE = NetworkBuffer.Enum(ParrotVariant.class);
    public static final Codec<ParrotVariant> CODEC = Codec.STRING.transform(ParrotVariant::fromKey, ParrotVariant::key);

    private final static ParrotVariant[] VALUES = values();

    private static ParrotVariant fromKey(String key) {
        for (ParrotVariant variant : VALUES) {
            if (variant.key.equals(key)) return variant;
        }
        throw new IllegalArgumentException("Unknown parrot variant: " + key);
    }
}
