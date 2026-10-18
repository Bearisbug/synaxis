package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * Custom payload channels of one plugin (API-M2-001, API-M2-008).
 * <p>
 * Channel ids are always {@code <plugin_id>:<name>}. A name may be given with or without the
 * prefix, but a prefix that is not this plugin's id is refused, which also keeps the {@code synaxis}
 * namespace reserved for the host.
 * <p>
 * Only the host implements this interface: a plugin is handed one and calls it.
 */
@ApiStatus.NonExtendable
public interface PluginChannels {

    /**
     * Registers an inbound handler on one channel of this plugin.
     *
     * @param name    the channel name, with or without the {@code <plugin_id>:} prefix
     * @param handler the handler; an exception it throws is caught and logged against this plugin
     * @throws IllegalArgumentException if the name carries another plugin's namespace or is not a
     *                                  usable channel name
     * @throws IllegalStateException    if that channel is already registered
     */
    void register(String name, PayloadHandler handler);

    /**
     * Sends a payload to one player on a channel of this plugin.
     *
     * @param player  the receiver
     * @param name    the channel name, with or without the {@code <plugin_id>:} prefix
     * @param payload the payload bytes, which SHOULD stay within 8 KiB
     * @throws IllegalArgumentException if the name carries another plugin's namespace or is not a
     *                                  usable channel name, or if the payload is over 1,048,576 bytes
     *                                  (what a client accepts), on the calling thread
     */
    void send(Player player, String name, byte[] payload);
}
