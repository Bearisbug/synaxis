package com.example.helloplugin;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginChannels;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The smallest useful plugin: it prints its lifecycle, listens for one event and echoes one channel
 * message back. See {@code docs/PLUGINS.md}.
 */
public final class HelloPlugin implements SynaxisPlugin {

    private static final PluginDescriptor DESCRIPTOR =
            new PluginDescriptor("hello", "1.0.0", PluginApi.API_VERSION);

    // The no argument constructor must register nothing, so the capabilities are only kept from
    // onEnable onwards and these two start as placeholders.
    private Logger logger = LoggerFactory.getLogger(HelloPlugin.class);
    private PluginChannels channels = null;

    @Override
    public PluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void onEnable(PluginContext context) {
        this.logger = context.logger();
        this.channels = context.channels();
        logger.info("hello-plugin is enabling, data directory: {}", context.dataDirectory());

        // Listeners live on a node the host detaches as a whole when the plugin is disabled.
        context.events().addListener(PlayerSpawnEvent.class,
                event -> logger.info("hello to {}", event.getPlayer().getUsername()));

        // "echo" becomes "hello:echo": a plugin can only name channels in its own namespace.
        channels.register("echo", this::echo);
    }

    @Override
    public void onDisable() {
        logger.info("hello-plugin is disabling");
    }

    private void echo(Player player, byte[] payload) {
        logger.info("echoing {} bytes back to {}", payload.length, player.getUsername());
        channels.send(player, "echo", payload);
    }
}
