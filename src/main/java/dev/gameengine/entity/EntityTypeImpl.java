package dev.gameengine.entity;

import dev.gameengine.registry.BuiltinRegistries;
import dev.gameengine.registry.Registry;
import dev.gameengine.registry.RegistryData;
import dev.gameengine.registry.RegistryKey;
import org.jetbrains.annotations.UnknownNullability;

@SuppressWarnings("removal")
record EntityTypeImpl(RegistryData.EntityEntry registry) implements EntityType {
    static final Registry<EntityType> REGISTRY = RegistryData.createStaticRegistry(BuiltinRegistries.ENTITY_TYPE,
            (namespace, properties) -> new EntityTypeImpl(RegistryData.entity(namespace, properties)));

    static @UnknownNullability EntityType get(RegistryKey<EntityType> key) {
        return REGISTRY.get(key);
    }

    @Override
    public String toString() {
        return name();
    }
}
