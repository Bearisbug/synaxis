package io.github.bearisbug.synaxis.app.instance;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.TickSchedulerThread;
import io.github.bearisbug.synaxis.app.scene.SceneService;
import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.PluginInstances;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The managed play instances of the process ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}, the
 * state machine of §11.1). One per process, built by the plugin host; each plugin sees it through the
 * {@link PluginInstances} of {@link #forPlugin}.
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

    private final ServerProcess process;
    private final InstanceLedger ledger;
    private final SceneService scenes;
    private final Thread startupThread;
    private final Durations durations;
    private final LongSupplier nanoClock;
    private final Map<String, PluginInstancesImpl> plugins = new ConcurrentHashMap<>();

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
        this.process = process;
        this.ledger = new InstanceLedger(ledger);
        this.scenes = scenes;
        this.startupThread = startupThread;
        this.durations = durations;
        this.nanoClock = nanoClock;
    }

    /**
     * The managed instances as one plugin sees them, the same view every time for the same plugin.
     *
     * @param pluginId the plugin
     * @return the managed instance capability of that plugin
     */
    public PluginInstances forPlugin(String pluginId) {
        return plugins.computeIfAbsent(pluginId, id -> new PluginInstancesImpl(id, this));
    }

    /**
     * Closes every instance of a plugin that is not closed yet, without calling the plugin: an
     * instance still preparing is dropped and never registered, the others are unregistered on the
     * calling thread, and one still being evacuated is finished at once (§11.1, forced close).
     *
     * @param pluginId the plugin being torn down
     * @param cause    {@link EndCause#PLUGIN_DISABLED} when its {@code onEnable} threw,
     *                 {@link EndCause#SHUTDOWN} when the process stops
     */
    public void reclaim(String pluginId, EndCause cause) {
        final PluginInstancesImpl instances = plugins.get(pluginId);
        if (instances != null) instances.reclaim(cause);
    }

    ServerProcess process() {
        return process;
    }

    InstanceLedger ledger() {
        return ledger;
    }

    SceneService scenes() {
        return scenes;
    }

    Durations durations() {
        return durations;
    }

    long now() {
        return nanoClock.getAsLong();
    }

    /**
     * Whether the calling thread may create an instance in place, waiting for its chunks.
     *
     * @return true on the thread that enables the plugins
     */
    boolean onStartupThread() {
        return Thread.currentThread() == startupThread;
    }

    /**
     * Whether the calling thread runs alongside no tick thread, where registering and unregistering an
     * instance is safe: the startup thread, before any tick, or the tick scheduler thread, which only
     * runs code of its own while the tick threads wait.
     *
     * @return true on either of them
     */
    boolean inSerialPart() {
        return onStartupThread() || Thread.currentThread() instanceof TickSchedulerThread;
    }
}
