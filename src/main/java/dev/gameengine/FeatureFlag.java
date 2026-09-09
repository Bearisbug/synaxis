package dev.gameengine;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.KeyPattern;
import dev.gameengine.registry.StaticProtocolObject;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

public sealed interface FeatureFlag extends StaticProtocolObject<FeatureFlag>, FeatureFlags permits FeatureFlagImpl {

    static Collection<FeatureFlag> values() {
        return FeatureFlagImpl.REGISTRY.values();
    }

    static @Nullable FeatureFlag fromKey(@KeyPattern String key) {
        return fromKey(Key.key(key));
    }

    static @Nullable FeatureFlag fromKey(Key key) {
        return FeatureFlagImpl.REGISTRY.get(key);
    }

    static @Nullable FeatureFlag fromId(int id) {
        return FeatureFlagImpl.REGISTRY.get(id);
    }

}
