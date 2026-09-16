package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.plugin.PayloadHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The single inbound listener of the host, routing custom payloads by channel namespace
 * (REQ-M2-006).
 * <p>
 * Channel ids are always {@code <plugin_id>:<name>}, so the namespace of a payload already names
 * the plugin that has to handle it. A payload on a channel nobody registered is dropped with one
 * warning instead of an error: an unknown channel is a client side fact, not a server fault.
 */
final class PluginChannelRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginChannelRouter.class);

    /**
     * The wire limit on a channel name (§8).
     */
    private static final int MAX_CHANNEL_ID_LENGTH = 256;

    private static final Pattern NAME = Pattern.compile("[a-z0-9._/-]+");

    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    private record Registration(String pluginId, PayloadHandler handler) {
    }

    /**
     * Resolves the channel id a plugin means by {@code name}.
     *
     * @param pluginId the plugin claiming the channel
     * @param name     the name it asked for, with or without its own namespace
     * @return the full channel id
     * @throws IllegalArgumentException if the name carries another namespace or is not usable
     */
    static String channelId(String pluginId, String name) {
        final int colon = name.indexOf(':');
        final String path;
        if (colon < 0) {
            path = name;
        } else {
            final String namespace = name.substring(0, colon);
            if (!namespace.equals(pluginId))
                throw new IllegalArgumentException("Refused channel \"" + name + "\" for plugin_id=" + pluginId
                        + ": a channel id is <plugin_id>:<name>, and " + pluginId + " does not own \""
                        + namespace + "\"");
            path = name.substring(colon + 1);
        }
        if (!NAME.matcher(path).matches())
            throw new IllegalArgumentException("Refused channel \"" + name + "\" for plugin_id=" + pluginId
                    + ": the name part must match " + NAME.pattern());
        final String channelId = pluginId + ":" + path;
        if (channelId.length() > MAX_CHANNEL_ID_LENGTH)
            throw new IllegalArgumentException("Refused channel \"" + channelId + "\": over "
                    + MAX_CHANNEL_ID_LENGTH + " characters");
        return channelId;
    }

    /**
     * Attaches the one listener this router needs.
     *
     * @param node the host node
     */
    void attach(EventNode<Event> node) {
        node.addListener(PlayerPluginMessageEvent.class, this::route);
    }

    /**
     * Registers one channel of one plugin.
     *
     * @param pluginId the owning plugin
     * @param name     the channel name, with or without the owning namespace
     * @param handler  the inbound handler
     * @return the resolved channel id
     */
    String register(String pluginId, String name, PayloadHandler handler) {
        final String channelId = channelId(pluginId, name);
        final Registration previous = registrations.putIfAbsent(channelId, new Registration(pluginId, handler));
        if (previous != null)
            throw new IllegalStateException("Channel " + channelId + " is already registered by plugin_id="
                    + previous.pluginId());
        return channelId;
    }

    /**
     * Drops every channel of one plugin, part of the one shot teardown of REQ-M2-004.
     *
     * @param pluginId the plugin being disabled
     */
    void unregisterAll(String pluginId) {
        registrations.values().removeIf(registration -> registration.pluginId().equals(pluginId));
    }

    private void route(PlayerPluginMessageEvent event) {
        final Player player = event.getPlayer();
        final String channel = event.getIdentifier();
        final Registration registration = registrations.get(channel);
        if (registration == null) {
            LOGGER.warn("Dropped a payload on an unregistered channel: connection_id={} channel={} bytes={}",
                    player.getPlayerConnection().connectionId(), channel, event.getMessage().length);
            return;
        }
        try {
            registration.handler().handle(player, event.getMessage());
        } catch (Throwable throwable) {
            LOGGER.error("Plugin channel handler failed: plugin_id={} channel={} connection_id={}",
                    registration.pluginId(), channel, player.getPlayerConnection().connectionId(), throwable);
        }
    }
}
