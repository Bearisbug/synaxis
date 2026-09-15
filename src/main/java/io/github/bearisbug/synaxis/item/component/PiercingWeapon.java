package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.sound.SoundEvent;
import org.jetbrains.annotations.Nullable;

public record PiercingWeapon(
        boolean dealsKnockback,
        boolean dismounts,
        @Nullable SoundEvent sound,
        @Nullable SoundEvent hitSound
) {
    public static final NetworkBuffer.Type<PiercingWeapon> NETWORK_TYPE = NetworkBufferTemplate.template(
            NetworkBuffer.BOOLEAN, PiercingWeapon::dealsKnockback,
            NetworkBuffer.BOOLEAN, PiercingWeapon::dismounts,
            SoundEvent.NETWORK_TYPE.optional(), PiercingWeapon::sound,
            SoundEvent.NETWORK_TYPE.optional(), PiercingWeapon::hitSound,
            PiercingWeapon::new);
    public static final Codec<PiercingWeapon> CODEC = StructCodec.struct(
            "deals_knockback", Codec.BOOLEAN.optional(true), PiercingWeapon::dealsKnockback,
            "dismounts", Codec.BOOLEAN.optional(false), PiercingWeapon::dismounts,
            "sound", SoundEvent.CODEC.optional(), PiercingWeapon::sound,
            "hit_sound", SoundEvent.CODEC.optional(), PiercingWeapon::hitSound,
            PiercingWeapon::new);
}
