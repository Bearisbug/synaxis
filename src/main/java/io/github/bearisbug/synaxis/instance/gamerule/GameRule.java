package io.github.bearisbug.synaxis.instance.gamerule;

import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.StaticProtocolObject;

/// Bindings for [Game rule](https://minecraft.wiki/w/Game_rule)
public sealed interface GameRule<T> extends GameRules, StaticProtocolObject<GameRule<?>> permits GameRuleImpl {
    static Registry<GameRule<?>> staticRegistry() {
        return GameRuleImpl.REGISTRY;
    }

    T defaultValue();
}
