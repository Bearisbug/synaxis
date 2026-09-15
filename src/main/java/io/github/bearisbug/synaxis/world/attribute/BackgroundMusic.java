package io.github.bearisbug.synaxis.world.attribute;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.sound.Music;
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
