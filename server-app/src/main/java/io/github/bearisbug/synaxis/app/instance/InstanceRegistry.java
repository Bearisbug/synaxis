package io.github.bearisbug.synaxis.app.instance;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.scene.SceneService;
import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.InstanceSpec;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.plugin.instance.PluginInstances;

import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The managed play instances of the process ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}, the
 * state machine of §11.1). One per process, built by the plugin host; each plugin sees it through the
 * {@link PluginInstances} of {@link #forPlugin}.
 * <p>
 * This class only carries the wiring so far: {@link #forPlugin} hands out a {@link PluginInstances}
 * whose every method throws {@link UnsupportedOperationException}, and {@link #reclaim} does nothing.
 */
public final class InstanceRegistry {

    /**
     * The time limits of the managed instances.
     *
     * @param prepareBudget how long generating and lighting may take before the instance closes with
     *                      {@link EndCause#PREPARE_FAILED}
     * @param evacuation    how long the players of a closing instance have to leave before the rest
     *                      are kicked
     */
    public record Durations(Duration prepareBudget, Duration evacuation) {

        /**
         * The limits of {@code API-PLAY-006}: 30 s to prepare, the budget of a match map, and 5 s to
         * evacuate.
         */
        public static final Durations DEFAULT = new Durations(Duration.ofSeconds(30), Duration.ofSeconds(5));
    }

    /**
     * @param process       the engine process
     * @param ledger        the event ledger, for the {@code play_instance_*} lines
     * @param scenes        the scene layer, which holds back what it sends a player being moved
     * @param startupThread the thread that enables the plugins; {@code create} on it prepares in place,
     *                      before the port is bound
     * @param durations     the time limits
     * @param nanoClock     a monotonic clock in nanoseconds, {@link System#nanoTime} outside tests
     */
    public InstanceRegistry(ServerProcess process, PluginAnalytics ledger, SceneService scenes, Thread startupThread,
                            Durations durations, LongSupplier nanoClock) {
    }

    /**
     * The managed instances as one plugin sees them.
     *
     * @param pluginId the plugin
     * @return the managed instance capability of that plugin
     */
    public PluginInstances forPlugin(String pluginId) {
        return new Unimplemented();
    }

    /**
     * Closes every instance of a plugin that is not closed yet, without calling the plugin: an
     * instance still preparing is dropped and never registered (§11.1, forced close).
     *
     * @param pluginId the plugin being torn down
     * @param cause    {@link EndCause#PLUGIN_DISABLED} when its {@code onEnable} threw,
     *                 {@link EndCause#SHUTDOWN} when the process stops
     */
    public void reclaim(String pluginId, EndCause cause) {
    }

    private static final class Unimplemented implements PluginInstances {

        private static UnsupportedOperationException unimplemented() {
            return new UnsupportedOperationException("the managed instances of API-PLAY-006 are not implemented yet");
        }

        @Override
        public ManagedInstance create(InstanceSpec spec) {
            throw unimplemented();
        }

        @Override
        public List<ManagedInstance> owned() {
            throw unimplemented();
        }
    }
}
