package io.github.bearisbug.synaxis.sound;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import org.jetbrains.annotations.Nullable;

public record CustomSoundEvent(Key key, @Nullable Float range) implements SoundEvent {
    public static final Codec<CustomSoundEvent> CODEC = StructCodec.struct(
            "sound_id", Codec.KEY, CustomSoundEvent::key,
            "range", Codec.FLOAT.optional(), CustomSoundEvent::range,
            CustomSoundEvent::new);
}
