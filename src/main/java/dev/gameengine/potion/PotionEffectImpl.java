package dev.gameengine.potion;

import dev.gameengine.registry.BuiltinRegistries;
import dev.gameengine.registry.Registry;
import dev.gameengine.registry.RegistryData;
import dev.gameengine.registry.RegistryKey;
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
