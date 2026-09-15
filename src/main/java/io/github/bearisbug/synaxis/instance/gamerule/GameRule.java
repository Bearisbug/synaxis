package io.github.bearisbug.synaxis.instance.gamerule;

import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.StaticProtocolObject;
import org.jetbrains.annotations.Nullable;

/// Bindings for [Game rule](https://minecraft.wiki/w/Game_rule)
public sealed interface GameRule<T> extends GameRules, StaticProtocolObject<GameRule<?>> permits GameRuleImpl {
    static Registry<GameRule<?>> staticRegistry() {
        return GameRuleImpl.REGISTRY;
    }

    @Deprecated(forRemoval = true)
    @SuppressWarnings("removal")
    @Override
    @Nullable
    default Object registry() {
        return null;
    }

    T defaultValue();
}
