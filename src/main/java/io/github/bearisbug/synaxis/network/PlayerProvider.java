package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;

/**
 * Used when you want to provide your own player object instead of using the default one.
 * <p>
 * Sets with {@link ConnectionManager#setPlayerProvider(PlayerProvider)}.
 */
@FunctionalInterface
public interface PlayerProvider {

    /**
     * Creates a new {@link Player} object based on his connection data.
     * <p>
     * Called on the read virtual thread of the connection, once the client has acknowledged its
     * login; the time spent here holds that thread, so nothing else from the client is read.
     * <p>
     * The player returned may be dropped without ever being tracked, when its uuid is already
     * taken or the connection manager has shut down. The constructor must therefore have no
     * outside side effect: registering the player by uuid, loading its data and the like belong
     * in {@link AsyncPlayerConfigurationEvent}. In online mode the previous session of the same
     * account may still be in the world at this point, and is only kicked afterwards.
     * <p>
     * {@link PlayerConnection#fetchCookie(String)} throws {@link IllegalStateException} before the
     * {@link Player} is constructed; called after it, waiting for its result here stalls the read
     * thread that would receive the answer.
     *
     * @param connection  the player connection
     * @param gameProfile the player game profile
     * @return a newly create {@link Player} object
     */
    Player createPlayer(PlayerConnection connection, GameProfile gameProfile);
}
