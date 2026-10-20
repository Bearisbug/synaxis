package io.github.bearisbug.synaxis.sound;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.registry.BuiltinRegistries;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.RegistryData;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.registry.StaticProtocolObject;
import org.jetbrains.annotations.UnknownNullability;

@SuppressWarnings("removal")
public record BuiltinSoundEvent(Key key, int id) implements StaticProtocolObject<BuiltinSoundEvent>, SoundEvent {
    static final Registry<BuiltinSoundEvent> REGISTRY = RegistryData.createStaticRegistry(BuiltinRegistries.SOUND_EVENT,
            (namespace, properties) -> new BuiltinSoundEvent(Key.key(namespace), properties.getInt("id")));

    static @UnknownNullability SoundEvent get(RegistryKey<? extends SoundEvent> key) {
        return REGISTRY.get(key.key());
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public String name() {
        return StaticProtocolObject.super.name();
    }
}
