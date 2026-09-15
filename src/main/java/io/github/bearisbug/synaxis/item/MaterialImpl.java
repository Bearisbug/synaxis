package io.github.bearisbug.synaxis.item;

import io.github.bearisbug.synaxis.registry.BuiltinRegistries;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.RegistryData;
import io.github.bearisbug.synaxis.registry.RegistryKey;
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
