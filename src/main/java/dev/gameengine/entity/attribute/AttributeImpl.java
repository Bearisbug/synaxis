package dev.gameengine.entity.attribute;

import dev.gameengine.registry.BuiltinRegistries;
import dev.gameengine.registry.Registry;
import dev.gameengine.registry.RegistryData;
import dev.gameengine.registry.RegistryKey;
import org.jetbrains.annotations.UnknownNullability;

@SuppressWarnings("removal")
record AttributeImpl(RegistryData.AttributeEntry registry) implements Attribute {
    static final Registry<Attribute> REGISTRY = RegistryData.createStaticRegistry(BuiltinRegistries.ATTRIBUTE,
            (namespace, properties) -> new AttributeImpl(RegistryData.attribute(namespace, properties)));

    static @UnknownNullability Attribute get(RegistryKey<Attribute> key) {
        return REGISTRY.get(key);
    }

    @Override
    public String toString() {
        return name();
    }
}
