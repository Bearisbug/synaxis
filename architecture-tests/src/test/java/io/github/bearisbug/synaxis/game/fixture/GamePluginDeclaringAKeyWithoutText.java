package io.github.bearisbug.synaxis.game.fixture;

import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import java.util.Set;

/**
 * Stands in for a plugin that sends a key of its own namespace, which no scan of {@code synaxis:}
 * strings can find, so it names the key in {@link #translationKeys()}. Not registered as a service:
 * it is only ever handed to the gate directly.
 */
public final class GamePluginDeclaringAKeyWithoutText implements SynaxisPlugin {

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("fixture", "1.0.0", PluginApi.API_VERSION);
    }

    @Override
    public Set<String> translationKeys() {
        return Set.of("fixture:declared-without-text");
    }

    @Override
    public void onEnable(PluginContext context) {
        throw new UnsupportedOperationException("a fixture is never enabled");
    }
}
