package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.entity.Player;

/**
 * Handler of one inbound custom payload (API-M2-001).
 */
@FunctionalInterface
public interface PayloadHandler {

    /**
     * Handles one payload received on the channel this handler is registered on.
     * <p>
     * The host calls it only for a connection in the play state, on the tick thread that owns the
     * sender. A payload that comes in before, while the connection is still being configured, is
     * dropped without reaching it.
     *
     * @param player  the sender
     * @param payload the payload bytes: the array of the packet itself, shared with the listeners of
     *                the plugin message event, so read it only and copy it first to make changes
     */
    void handle(Player player, byte[] payload);
}
