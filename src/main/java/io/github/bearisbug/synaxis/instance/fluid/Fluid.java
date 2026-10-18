package io.github.bearisbug.synaxis.instance.fluid;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.KeyPattern;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.RegistryData;
import io.github.bearisbug.synaxis.registry.StaticProtocolObject;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

public sealed interface Fluid extends StaticProtocolObject<Fluid>, Fluids permits FluidImpl {

    /**
     * Returns the legacy registry data backing this fluid.
     *
     * @return the legacy registry data
     * @deprecated registry data will no longer be exposed
    */
    @Override
    @Deprecated(forRemoval = true)
    @SuppressWarnings("removal")
    @Contract(pure = true)
    RegistryData.FluidEntry registry();

    static Collection<Fluid> values() {
        return FluidImpl.REGISTRY.values();
    }

    static @Nullable Fluid fromKey(@KeyPattern String key) {
        try {
            return fromKey(Key.key(key));
        } catch (InvalidKeyException _) {
            return null;
        }
    }

    static @Nullable Fluid fromKey(Key key) {
        return FluidImpl.REGISTRY.get(key);
    }

    static @Nullable Fluid fromId(int id) {
        return FluidImpl.REGISTRY.get(id);
    }

    static Registry<Fluid> staticRegistry() {
        return FluidImpl.REGISTRY;
    }
}
