package dev.gameengine.item;

import dev.gameengine.registry.BuiltinRegistries;
import dev.gameengine.registry.Registry;
import dev.gameengine.registry.RegistryData;
import dev.gameengine.registry.RegistryKey;
import org.jetbrains.annotations.UnknownNullability;

@SuppressWarnings("removal")
record MaterialImpl(RegistryData.MaterialEntry registry) implements Material {
    static final Registry<Material> REGISTRY = RegistryData.createStaticRegistry(BuiltinRegistries.ITEM,
            (namespace, properties) -> new MaterialImpl(RegistryData.material(namespace, properties)));

    static @UnknownNullability Material get(RegistryKey<Material> key) {
        return REGISTRY.get(key);
    }

    @Override
    public String toString() {
        return name();
    }
}
