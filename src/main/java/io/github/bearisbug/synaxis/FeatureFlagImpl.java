package io.github.bearisbug.synaxis;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.registry.BuiltinRegistries;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.RegistryData;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import org.jetbrains.annotations.UnknownNullability;

@SuppressWarnings("removal")
record FeatureFlagImpl(RegistryData.FeatureFlagEntry registry) implements FeatureFlag {
    static final Registry<FeatureFlag> REGISTRY = RegistryData.createStaticRegistry(BuiltinRegistries.FEATURE_FLAG,
            (namespace, properties) -> new FeatureFlagImpl(RegistryData.featureFlag(namespace, properties)));

    static @UnknownNullability FeatureFlag get(RegistryKey<FeatureFlag> key) {
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
