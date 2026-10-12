package io.github.bearisbug.synaxis.plugin.instance;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * The managed play instances of one plugin ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}).
 * <p>
 * Only the host implements this interface: a plugin is handed one and calls it.
 */
@ApiStatus.NonExtendable
public interface PluginInstances {

    /**
     * Creates a managed instance and prepares it the way a match map is prepared: an unregistered
     * instance whose chunks light themselves, every chunk within the radius loaded (generated on the
     * engine's virtual threads), the whole map lit once they are all in, automatic chunk loading off,
     * then registered.
     * <p>
     * Called on the startup thread ({@code onEnable}, before the port is bound), the preparation is
     * done in place and the instance comes back {@link ManagedInstance.State#OPEN}. Called on the
     * tick scheduler thread (a task of {@code scheduleTask}), the instance comes back
     * {@link ManagedInstance.State#PREPARING} at once: generation and lighting run outside the tick,
     * and the instance is registered and turns {@code OPEN} in the next serial part after they are
     * done. If generation or lighting throws, or they are not done within 30 s, the instance turns
     * {@link ManagedInstance.State#CLOSED} with {@link EndCause#PREPARE_FAILED}.
     *
     * @param spec what to create
     * @return the instance, owned by this plugin
     * @throws IllegalStateException    if called on any other thread
     * @throws IllegalArgumentException if the dimension type of the spec was not registered before
     *                                  the port was bound
     */
    ManagedInstance create(InstanceSpec spec);

    /**
     * The instances this plugin created that are not closed yet.
     *
     * @return those instances, in creation order
     */
    List<ManagedInstance> owned();
}
