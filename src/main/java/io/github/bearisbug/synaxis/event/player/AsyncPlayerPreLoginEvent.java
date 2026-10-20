package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.event.trait.AsyncEvent;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.network.plugin.LoginPlugin;
import io.github.bearisbug.synaxis.network.plugin.LoginPluginMessageProcessor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Called before the player initialization, it can be used to kick the player before any connection
 * or to change his final username/uuid.
 * <ul>
 *     <li>Dispatched on a virtual thread, the authentication thread in online mode and the login
 *     thread in offline mode, so a listener may block.</li>
 *     <li>{@link #getGameProfile()} is the authenticated profile when the dispatch starts (the
 *     offline UUID in offline mode).</li>
 *     <li>The event cannot be cancelled: a listener refuses the login with
 *     {@code getConnection().kick(Component)}. The listeners after it still run, and check
 *     {@code getConnection().isOnline()} first when that matters to them.</li>
 *     <li>{@link #setGameProfile(GameProfile)} replaces the identity everything keyed on the UUID
 *     uses afterwards (persistence, permissions, ledger), see its warning.</li>
 * </ul>
 */
public class AsyncPlayerPreLoginEvent implements AsyncEvent {

    private final PlayerConnection connection;
    private GameProfile gameProfile;
    private final LoginPluginMessageProcessor pluginMessageProcessor;

    public AsyncPlayerPreLoginEvent(PlayerConnection connection,
                                    GameProfile gameProfile,
                                    LoginPluginMessageProcessor pluginMessageProcessor) {
        this.connection = connection;
        this.gameProfile = gameProfile;
        this.pluginMessageProcessor = pluginMessageProcessor;
    }

    public PlayerConnection getConnection() {
        return connection;
    }

    public GameProfile getGameProfile() {
        return gameProfile;
    }

    /**
     * Replaces the profile the player logs in with.
     * <p>
     * Everything keyed on the UUID afterwards (persistence, permissions, ledger) follows the new
     * profile, while the checks that already ran judged the previous one: the host's bans and
     * whitelist run before every plugin listener on the authenticated UUID, so a new UUID set here
     * was never checked by them. Only for the cases that do have to change the identity, such as
     * proxy forwarding.
     *
     * @param gameProfile the profile to log in with
     */
    public void setGameProfile(GameProfile gameProfile) {
        this.gameProfile = gameProfile;
    }

    /**
     * Sends a login plugin message request. Can be useful to negotiate with modded clients or
     * proxies before moving on to the Configuration state.
     * <p>
     * The future completes with the answer of the client. If the connection disconnects or leaves
     * the login state first, it completes exceptionally with an {@link IllegalStateException}; the
     * engine sets no other timeout, so wait with {@code orTimeout} or a timed {@code get}. Callbacks
     * that depend on it may run synchronously on the thread that completes it: the read thread for
     * an answer, the disconnecting thread (read, tick or write thread), the write thread when the
     * connection leaves the login state. Use the {@code *Async} variants for slow work.
     *
     * @param channel        the plugin message channel
     * @param requestPayload the contents of the plugin message, can be null for empty
     * @return a CompletableFuture for the response
     * @throws IllegalStateException if the connection already left the login state or disconnected,
     *                               as it has once the event returned and the login went on
     */
    public CompletableFuture<LoginPlugin.Response> sendPluginRequest(String channel, byte[] requestPayload) {
        return pluginMessageProcessor.request(channel, requestPayload);
    }

    /**
     * @return the name of the profile
     * @deprecated use {@code getGameProfile().name()}
     */
    @Deprecated
    public String getUsername() {
        return gameProfile.name();
    }

    /**
     * @param username the new name of the profile
     * @deprecated use {@code setGameProfile(new GameProfile(getGameProfile().uuid(), name,
     * getGameProfile().properties()))}
     */
    @Deprecated
    public void setUsername(String username) {
        this.gameProfile = new GameProfile(gameProfile.uuid(), username, gameProfile.properties());
    }

    /**
     * @return the UUID of the profile
     * @deprecated use {@code getGameProfile().uuid()}
     */
    @Deprecated
    public UUID getPlayerUuid() {
        return gameProfile.uuid();
    }
}
