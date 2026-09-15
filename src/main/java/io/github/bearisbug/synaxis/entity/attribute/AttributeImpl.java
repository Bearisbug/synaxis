package io.github.bearisbug.synaxis.entity.attribute;

import io.github.bearisbug.synaxis.registry.BuiltinRegistries;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.RegistryData;
import io.github.bearisbug.synaxis.registry.RegistryKey;
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
