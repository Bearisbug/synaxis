package io.github.bearisbug.synaxis.app.instance;

import io.github.bearisbug.synaxis.TickSchedulerThread;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.InstanceSpec;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.plugin.instance.PluginInstances;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;

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

    PluginInstancesImpl(String pluginId, InstanceRegistry registry) {
        this.pluginId = pluginId;
        this.registry = registry;
    }

    @Override
    public ManagedInstance create(InstanceSpec spec) {
        if (tornDown)
            throw new IllegalStateException("plugin_id=" + pluginId + " was taken back and creates no more managed instances");
        final boolean inPlace = registry.onStartupThread();
        if (!inPlace && !(Thread.currentThread() instanceof TickSchedulerThread))
            throw new IllegalStateException("a managed instance is created on the startup thread or the tick scheduler "
                    + "thread, not on " + Thread.currentThread().getName());
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
    void closed(ManagedInstanceImpl managed) {
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
        final RegistryKey<DimensionType> key = Key.parseable(name)
                ? registry.process().dimensionType().getKey(Key.key(name)) : null;
        if (key == null)
            throw new IllegalArgumentException("the dimension type " + name + " was not registered before the port was bound");
        return key;
    }
}
