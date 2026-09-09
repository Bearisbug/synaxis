package dev.gameengine.world.attribute;

import dev.gameengine.codec.Codec;
import dev.gameengine.codec.StructCodec;
import dev.gameengine.sound.Music;
import org.jetbrains.annotations.Nullable;

public record BackgroundMusic(
        @Nullable Music music,
        @Nullable Music creativeMusic,
        @Nullable Music underwaterMusic
) {
    public static final BackgroundMusic EMPTY = new BackgroundMusic(null, null, null);
    public static final BackgroundMusic OVERWORLD = new BackgroundMusic(Music.GAME, Music.CREATIVE, null);

    public static final Codec<BackgroundMusic> CODEC = StructCodec.struct(
            "default", Music.CODEC.optional(), BackgroundMusic::music,
            "creative", Music.CODEC.optional(), BackgroundMusic::creativeMusic,
            "underwater", Music.CODEC.optional(), BackgroundMusic::underwaterMusic,
            BackgroundMusic::new);
    
}
