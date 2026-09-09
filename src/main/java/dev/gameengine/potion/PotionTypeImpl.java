package dev.gameengine.potion;

import net.kyori.adventure.key.Key;
import dev.gameengine.registry.BuiltinRegistries;
import dev.gameengine.registry.Registry;
import dev.gameengine.registry.RegistryData;
import dev.gameengine.registry.RegistryKey;
import org.jetbrains.annotations.UnknownNullability;

@SuppressWarnings("removal")
record PotionTypeImpl(Key key, int id) implements PotionType {
    static final Registry<PotionType> REGISTRY = RegistryData.createStaticRegistry(BuiltinRegistries.POTION,
            (namespace, properties) -> new PotionTypeImpl(Key.key(namespace), properties.getInt("id")));

    static @UnknownNullability PotionType get(RegistryKey<PotionType> key) {
        return REGISTRY.get(key);
    }

    @Override
    public String toString() {
        return name();
    }
}
