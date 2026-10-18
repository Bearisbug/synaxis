package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.plugin.LogBudget;
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
 * the plugin that has to handle it. A payload on a channel nobody registered is dropped with a
 * warning instead of an error: an unknown channel is a client side fact, not a server fault. The
 * channels of the {@code minecraft} namespace every client sends, such as {@code minecraft:brand},
 * are dropped without one.
 * <p>
 * A handler only gets the payloads of a connection in the play state, on the tick thread that owns the
 * sender ({@code docs/DESIGN-M2.md} {@code API-M2-001}): in a gameplay process a connection reaches the
 * play state only once its handshake and its admission are through. The
 * channels of the host that speak during the configuration, the handshake and the diagnostic echo of
 * the core mode, are registered as such and get those payloads too.
 * <p>
 * Which is also why every line of this class is written through a {@link LogBudget}: a client
 * picks the channel of every payload it sends and how many it sends, so neither a drop nor a
 * handler failure may be written once per payload ({@code docs/DESIGN.md} §15).
 */
final class PluginChannelRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginChannelRouter.class);

    /**
     * The wire limit on a channel name (§8).
     */
    private static final int MAX_CHANNEL_ID_LENGTH = 256;

    private static final Pattern NAME = Pattern.compile("[a-z0-9._/-]+");

    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    /**
     * The budget of the drop line. A client invents the channel ids, so this one is shared by every
     * unregistered channel there is: a budget per channel id would be a budget per payload.
     */
    private final LogBudget drops = new LogBudget(LOGGER, "payloads");

    /** The budget of the line of a payload dropped before the play state, shared by every channel. */
    private final LogBudget early = new LogBudget(LOGGER, "payloads outside the play state");

    /**
     * @param failures             the budget of this handler's failures, one per registration so that a
     *                             plugin failing on every payload cannot silence another plugin's first
     *                             stack
     * @param configurationAllowed whether the handler also gets the payloads of a connection that is not
     *                             in the play state, which only a channel of the host does
     */
    private record Registration(String pluginId, PayloadHandler handler, LogBudget failures,
                                boolean configurationAllowed) {
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
     * Registers one channel of one plugin, whose handler gets the payloads of a connection in the play
     * state only.
     *
     * @param pluginId the owning plugin
     * @param name     the channel name, with or without the owning namespace
     * @param handler  the inbound handler
     * @return the resolved channel id
     */
    String register(String pluginId, String name, PayloadHandler handler) {
        return register(pluginId, name, handler, false);
    }

    /**
     * Registers one channel.
     *
     * @param pluginId             the owning plugin, the reserved id for a channel of the host
     * @param name                 the channel name, with or without the owning namespace
     * @param handler              the inbound handler
     * @param configurationAllowed whether the handler also gets the payloads of a connection that is not
     *                             in the play state
     * @return the resolved channel id
     */
    String register(String pluginId, String name, PayloadHandler handler, boolean configurationAllowed) {
        final String channelId = channelId(pluginId, name);
        final Registration previous = registrations.putIfAbsent(channelId,
                new Registration(pluginId, handler, new LogBudget(LOGGER, "failures"), configurationAllowed));
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
        final PlayerConnection connection = player.getPlayerConnection();
        if (registration == null) {
            // Looked up first: a plugin may have the id minecraft, and its channels are registered
            if (channel.startsWith("minecraft:")) return;
            drops.warn("Dropped a payload on an unregistered channel: connection_id={} bytes={} channel={}",
                    connection.connectionId(), event.getMessage().length, channel);
            return;
        }
        if (!registration.configurationAllowed() && connection.getServerState() != ConnectionState.PLAY) {
            early.warn("Dropped a payload outside the play state: connection_id={} state={} channel={}",
                    connection.connectionId(), connection.getServerState(), channel);
            return;
        }
        try {
            registration.handler().handle(player, event.getMessage());
        } catch (Throwable throwable) {
            registration.failures().error(throwable,
                    "Plugin channel handler failed: plugin_id={} channel={} connection_id={}",
                    registration.pluginId(), channel, player.getPlayerConnection().connectionId());
        }
    }
}
