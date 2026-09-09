package dev.gameengine.item.component;

import dev.gameengine.codec.Codec;
import dev.gameengine.network.NetworkBuffer;

public enum TropicalFishPattern {
    KOB(false, 0),
    SUNSTREAK(false, 1),
    SNOOPER(false, 2),
    DASHER(false, 3),
    BRINELY(false, 4),
    SPOTTY(false, 5),
    FLOPPER(true, 0),
    STRIPEY(true, 1),
    GLITTER(true, 2),
    BLOCKFISH(true, 3),
    BETTY(true, 4),
    CLAYFISH(true, 5);

    public static final NetworkBuffer.Type<TropicalFishPattern> NETWORK_TYPE = NetworkBuffer.VAR_INT.transform(TropicalFishPattern::fromId, TropicalFishPattern::id);
    public static final Codec<TropicalFishPattern> CODEC = Codec.Enum(TropicalFishPattern.class);

    private final static TropicalFishPattern[] VALUES = values();

    public static TropicalFishPattern fromId(int id) {
        for (TropicalFishPattern pattern : VALUES) {
            if (pattern.id() == id) {
                return pattern;
            }
        }
        throw new IllegalArgumentException("Invalid pattern id: " + id);
    }

    private final int id;

    TropicalFishPattern(boolean isLarge, int id) {
        this.id = (isLarge ? 1 : 0) | (id << 8);
    }

    public int id() {
        return this.id;
    }
}
