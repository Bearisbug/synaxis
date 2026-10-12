package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
import io.github.bearisbug.synaxis.plugin.play.ActionHandler;
import io.github.bearisbug.synaxis.plugin.play.Hud;
import io.github.bearisbug.synaxis.plugin.play.PluginPlay;
import io.github.bearisbug.synaxis.plugin.play.SceneSpec;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The host's scene layer ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-002}, {@code API-PLAY-005},
 * §6.4.3): the channel {@code synaxis:play}, the scene and the widget table of every connection, and
 * the actions of the plugins. One per process, built by the plugin host; each plugin sees it through
 * the {@link PluginPlay} of {@link #forPlugin}.
 * <p>
 * This class only carries the wiring so far: {@link #forPlugin} hands out a {@link PluginPlay} whose
 * every method throws {@link UnsupportedOperationException}, and the other methods do nothing.
 */
public final class SceneService {

    /**
     * The host channel of the scene layer, in the reserved namespace.
     */
    public static final String CHANNEL = "synaxis:play";

    /**
     * @param process    the engine process
     * @param sceneOwner the id of the plugin allowed to enter and leave scenes, the holder of the
     *                   admission slot; null while nobody holds it
     * @param ledger     the event ledger, for {@code play_scene_entered} and {@code play_scene_ready}
     * @param nanoClock  a monotonic clock in nanoseconds, {@link System#nanoTime} outside tests; the
     *                   rate limit and the cooldowns run on it
     */
    public SceneService(ServerProcess process, Supplier<@Nullable String> sceneOwner, PluginAnalytics ledger,
                        LongSupplier nanoClock) {
    }

    /**
     * Hangs the listeners of the scene layer on the host node: the flush at the end of every
     * player's tick and the cleanup when a connection goes.
     *
     * @param hostNode the host node
     */
    public void attach(EventNode<Event> hostNode) {
    }

    /**
     * Handles one payload a client sent on {@link #CHANNEL}, on the player's tick thread.
     *
     * @param player  the sender
     * @param payload the payload
     */
    public void receive(Player player, byte[] payload) {
    }

    /**
     * The scene layer as one plugin sees it.
     *
     * @param pluginId the plugin
     * @return the play capability of that plugin
     */
    public PluginPlay forPlugin(String pluginId) {
        return new Unimplemented();
    }

    /**
     * Closes the action registration of a plugin, once its {@code onEnable} returned: from then on
     * {@link PluginPlay#onAction} throws.
     *
     * @param pluginId the plugin
     */
    public void sealActions(String pluginId) {
    }

    /**
     * Takes back the actions of a plugin being torn down.
     *
     * @param pluginId the plugin
     */
    public void reclaim(String pluginId) {
    }

    /**
     * Holds back everything queued for a player until a move into another instance completes, so
     * that the client sees the respawn before the scene (§6.4.3).
     *
     * @param player the player being moved
     * @param move   completes when the move is done, whichever way it went
     */
    public void holdUntil(Player player, CompletableFuture<?> move) {
    }

    private static final class Unimplemented implements PluginPlay {

        private static UnsupportedOperationException unimplemented() {
            return new UnsupportedOperationException("the scene layer of API-PLAY-005 is not implemented yet");
        }

        @Override
        public void enter(Player player, SceneSpec scene) {
            throw unimplemented();
        }

        @Override
        public void leave(Player player) {
            throw unimplemented();
        }

        @Override
        public Optional<String> currentScene(Player player) {
            throw unimplemented();
        }

        @Override
        public Hud hud(Player player) {
            throw unimplemented();
        }

        @Override
        public void onAction(String actionId, ActionHandler handler) {
            throw unimplemented();
        }
    }
}
