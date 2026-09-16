package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;

/**
 * The part of the startup sequence a match needs but does not do itself: in production the host
 * registers the one shallow dimension type before it enables any plugin (API-M2-010 step 3).
 */
final class MatchSupport {

    private MatchSupport() {
    }

    static RegistryKey<DimensionType> registerMatchDimension(Env env) {
        final Key key = Key.key(PluginApi.MATCH_DIMENSION_TYPE);
        final RegistryKey<DimensionType> existing = env.process().dimensionType().getKey(key);
        if (existing != null) return existing;
        return env.process().dimensionType().register(key,
                DimensionType.builder().minY(0).height(64).logicalHeight(64).build());
    }
}
