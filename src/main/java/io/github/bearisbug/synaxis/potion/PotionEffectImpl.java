package io.github.bearisbug.synaxis.potion;

import io.github.bearisbug.synaxis.registry.BuiltinRegistries;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.RegistryData;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import org.jetbrains.annotations.UnknownNullability;

@SuppressWarnings("removal")
record PotionEffectImpl(RegistryData.PotionEffectEntry registry) implements PotionEffect {
    static final Registry<PotionEffect> REGISTRY = RegistryData.createStaticRegistry(BuiltinRegistries.MOB_EFFECT,
            (namespace, properties) -> new PotionEffectImpl(RegistryData.potionEffect(namespace, properties)));

    static @UnknownNullability PotionEffect get(RegistryKey<PotionEffect> key) {
        return REGISTRY.get(key);
    }

    @Override
    public String toString() {
        return name();
    }
}
