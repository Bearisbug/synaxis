package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;

import java.util.Set;

/**
 * The sample game rush ({@code docs/DESIGN-PLAY.md} §6.7, {@code API-PLAY-010}); the skeleton of
 * RUN-077 group R, with the descriptor and the keys and without the game.
 */
public final class RushPlugin implements SynaxisPlugin {

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor("rush", "0.1.0", PluginApi.API_VERSION);

    @Override
    public PluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Set<String> translationKeys() {
        return RushHud.keys();
    }

    @Override
    public void onEnable(PluginContext context) {
    }
}
