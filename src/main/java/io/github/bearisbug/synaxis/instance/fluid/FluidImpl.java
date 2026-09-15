package io.github.bearisbug.synaxis.instance.fluid;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.registry.BuiltinRegistries;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.RegistryData;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import org.jetbrains.annotations.UnknownNullability;

@SuppressWarnings("removal")
record FluidImpl(RegistryData.FluidEntry registry) implements Fluid {
    static final Registry<Fluid> REGISTRY = RegistryData.createStaticRegistry(BuiltinRegistries.FLUID,
            (namespace, properties) -> new FluidImpl(RegistryData.fluid(namespace, properties)));


    static @UnknownNullability Fluid get(RegistryKey<Fluid> key) {
        return REGISTRY.get(key);
    }

    @Override
    public Key key() {
        return registry.key();
    }

    @Override
    public int id() {
        return registry.id();
    }
}
