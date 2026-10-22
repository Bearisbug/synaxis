package io.github.bearisbug.synaxis.app.instance;

import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.InstanceSpec;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.plugin.instance.PluginInstances;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The managed instances of one plugin ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}).
 * <p>
 * {@link #create} takes its path from the calling thread: on the startup thread the instance is
 * prepared in place and comes back open, on the tick scheduler thread it comes back preparing and is
 * registered in a later serial part, and any other thread is refused.
 */
final class PluginInstancesImpl implements PluginInstances {

    private final String pluginId;
    private final InstanceRegistry registry;
    /** The instances not finished closing yet, in creation order: the ones a forced close still has to reach. */
    private final List<ManagedInstanceImpl> live = new CopyOnWriteArrayList<>();
    /** The last number handed out per instance name, so that each dimension name is used once. */
    private final Map<String, AtomicInteger> serials = new ConcurrentHashMap<>();
    /** Set once the plugin is taken back: an instance it created afterwards would never be closed by force. */
    private volatile boolean tornDown;

    /*
     * The WARN and ERROR lines of the plugin's instances, one budget per kind of line: the host's log rule
     * (ClientDrivenLogArchitectureTest) lets no class of this package write one itself. Per plugin, so
     * that one plugin whose instances keep failing cannot hide another's; each keeps the logger of the
     * class whose line it is.
     */
    final LogBudget prepareFailures =
            new LogBudget(LoggerFactory.getLogger(ManagedInstanceImpl.class), "managed instances that failed to prepare");
    final LogBudget settleTimeouts =
            new LogBudget(LoggerFactory.getLogger(ManagedInstanceImpl.class), "managed instances closed at their settle timeout");
    final LogBudget missingExits =
            new LogBudget(LoggerFactory.getLogger(Evacuation.class), "players kicked for want of an exit");
    final LogBudget failedMoves =
            new LogBudget(LoggerFactory.getLogger(ManagedInstanceImpl.class), "moves the engine failed");

    PluginInstancesImpl(String pluginId, InstanceRegistry registry) {
        this.pluginId = pluginId;
        this.registry = registry;
    }

    @Override
    public ManagedInstance create(InstanceSpec spec) {
        if (tornDown)
            throw new IllegalStateException("plugin_id=" + pluginId + " was taken back and creates no more managed instances");
        if (!registry.inSerialPart())
            throw new IllegalStateException("plugin_id=" + pluginId + " creates a managed instance in onEnable before the "
                    + "port is bound, or in a task of scheduleTask; " + Thread.currentThread().getName()
                    + " is neither: hand it over with scheduler().scheduleTask");
        final boolean inPlace = registry.onStartupThread();
        final RegistryKey<DimensionType> dimensionType = dimensionType(spec.dimensionType());
        final int serial = serials.computeIfAbsent(spec.name(), _ -> new AtomicInteger()).incrementAndGet();
        final ManagedInstanceImpl managed = new ManagedInstanceImpl(registry, this, spec, dimensionType, serial);
        live.add(managed);
        if (inPlace) managed.prepareInPlace();
        else managed.prepareOutsideTheTick();
        return managed;
    }

    @Override
    public List<ManagedInstance> owned() {
        return Collections.unmodifiableList(live.stream()
                .filter(managed -> managed.state() != ManagedInstance.State.CLOSED)
                .toList());
    }

    String pluginId() {
        return pluginId;
    }

    /**
     * Forgets an instance that finished closing: unregistered, or dropped before it ever was.
     *
     * @param managed the instance
     */
    void forget(ManagedInstanceImpl managed) {
        live.remove(managed);
    }

    /**
     * Closes by force every instance of the plugin not finished closing yet; the plugin creates no more.
     *
     * @param cause {@link EndCause#PLUGIN_DISABLED} or {@link EndCause#SHUTDOWN}
     */
    void reclaim(EndCause cause) {
        this.tornDown = true;
        for (ManagedInstanceImpl managed : live) managed.reclaim(cause);
    }

    private RegistryKey<DimensionType> dimensionType(String name) {
        if (!Key.parseable(name))
            throw new IllegalArgumentException("plugin_id=" + pluginId + ": the dimension type \"" + name + "\" is not a valid key");
        final RegistryKey<DimensionType> key = registry.process().dimensionType().getKey(Key.key(name));
        if (key == null)
            throw new IllegalArgumentException("plugin_id=" + pluginId + ": the dimension type " + name
                    + " was not registered before the port was bound");
        return key;
    }
}
