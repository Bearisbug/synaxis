package io.github.bearisbug.synaxis;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.KeyPattern;
import io.github.bearisbug.synaxis.registry.StaticProtocolObject;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

public sealed interface FeatureFlag extends StaticProtocolObject<FeatureFlag>, FeatureFlags permits FeatureFlagImpl {

    static Collection<FeatureFlag> values() {
        return FeatureFlagImpl.REGISTRY.values();
    }

    static @Nullable FeatureFlag fromKey(@KeyPattern String key) {
        try {
            return fromKey(Key.key(key));
        } catch (InvalidKeyException _) {
            return null;
        }
    }

    static @Nullable FeatureFlag fromKey(Key key) {
        return FeatureFlagImpl.REGISTRY.get(key);
    }

    static @Nullable FeatureFlag fromId(int id) {
        return FeatureFlagImpl.REGISTRY.get(id);
    }

}
