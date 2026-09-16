package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.PayloadHandler;
import io.github.bearisbug.synaxis.plugin.PluginChannels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Channel capability of one plugin, forcing its own namespace on every channel id (REQ-M2-006).
 *
 * @param pluginId the owning plugin
 * @param router   the host router every channel of every plugin lands in
 */
record PluginChannelsImpl(String pluginId, PluginChannelRouter router) implements PluginChannels {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginChannelsImpl.class);

    @Override
    public void register(String name, PayloadHandler handler) {
        final String channelId = router.register(pluginId, name, handler);
        LOGGER.info("Registered channel: plugin_id={} channel={}", pluginId, channelId);
    }

    @Override
    public void send(Player player, String name, byte[] payload) {
        player.sendPluginMessage(PluginChannelRouter.channelId(pluginId, name), payload);
    }
}
