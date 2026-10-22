package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.plugin.PayloadHandler;
import io.github.bearisbug.synaxis.utils.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * the core mode, are registered as such and get those payloads too. A payload dropped for coming before
 * the play state gets a warning on a plugin's channel, and none on the host's {@code synaxis:play}.
 * <p>
 * Which is also why every line of this class is written through a {@link LogBudget}: a client
 * picks the channel of every payload it sends and how many it sends, so neither a drop nor a
 * handler failure may be written once per payload ({@code docs/DESIGN.md} §15).
 */
final class PluginChannelRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginChannelRouter.class);

    /**
     * The wire limit on a channel name (§8), its characters being the bytes of the ASCII {@link PluginIds} takes.
     */
    private static final int MAX_CHANNEL_ID_LENGTH = 256;

    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    /**
     * The plugins already taken back: a channel one of them registers afterwards, from a thread it left running,
     * would stay with nothing to take it back.
     */
    private final Set<String> takenBack = ConcurrentHashMap.newKeySet();

    /**
     * The budget of the drop line. A client invents the channel ids, so this one is shared by every
     * unregistered channel there is: a budget per channel id would be a budget per payload.
     */
    private final LogBudget drops = new LogBudget(LOGGER, "payloads");

    /** The budget of the line of a payload dropped before the play state, shared by every plugin channel. */
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
     * Resolves the channel id a plugin means by {@code name}, under the rule of {@link PluginIds}.
     *
     * @param pluginId the plugin claiming the channel
     * @param name     the name it asked for, with or without its own namespace
     * @return the full channel id
     * @throws IllegalArgumentException if the name carries another namespace or is not usable
     */
    static String channelId(String pluginId, String name) {
        return PluginIds.qualify(pluginId, name, MAX_CHANNEL_ID_LENGTH, "a channel id");
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
     * @return the resolved channel id, null when the plugin is already taken back and the channel is dropped
     */
    @Nullable String register(String pluginId, String name, PayloadHandler handler) {
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
     * @return the resolved channel id, null when the plugin is already taken back and the channel is dropped
     */
    @Nullable String register(String pluginId, String name, PayloadHandler handler, boolean configurationAllowed) {
        final String channelId = channelId(pluginId, name);
        final Registration registration =
                new Registration(pluginId, handler, new LogBudget(LOGGER, "failures"), configurationAllowed);
        final Registration previous = registrations.putIfAbsent(channelId, registration);
        if (previous != null)
            throw new IllegalStateException("Channel " + channelId + " is already registered by plugin_id="
                    + previous.pluginId());
        // Read once the registration is in: unregisterAll marks the plugin before it sweeps, so either
        // the sweep finds this registration or this finds the mark
        if (takenBack.contains(pluginId)) {
            registrations.remove(channelId, registration);
            return null;
        }
        return channelId;
    }

    /**
     * Drops every channel of one plugin, part of the one shot teardown of REQ-M2-004; a channel it
     * registers afterwards is dropped as well.
     *
     * @param pluginId the plugin being disabled
     */
    void unregisterAll(String pluginId) {
        takenBack.add(pluginId);
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
                    connection.connectionId(), event.getMessage().length, StringUtils.escapeForLog(channel));
            return;
        }
        if (!registration.configurationAllowed() && connection.getServerState() != ConnectionState.PLAY) {
            // The host's own synaxis:play is dropped without a line: a process with no scene owner writes none
            // about that channel (docs/DESIGN-PLAY.md API-PLAY-002)
            if (!registration.pluginId().equals(PluginDescriptors.RESERVED_ID))
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
