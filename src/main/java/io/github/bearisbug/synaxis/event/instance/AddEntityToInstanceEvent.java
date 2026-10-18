package io.github.bearisbug.synaxis.event.instance;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.EntityEvent;
import io.github.bearisbug.synaxis.event.trait.InstanceEvent;
import io.github.bearisbug.synaxis.instance.Instance;

/**
 * Called by an Instance when an entity is added to it.
 * Can be used attach data.
 * <p>
 * Called before anything of the change is done: cancelled, the entity stays where it was and the future of
 * {@link Entity#setInstance(Instance, io.github.bearisbug.synaxis.coordinate.Pos)} fails with a
 * {@link CancelledException}. A player is asked once the chunks around its new position are loaded, before its
 * client is told of the new instance, so a cancelled change of instance sends it nothing. The first spawn, after the
 * login or a configuration, comes once the client was sent its world: cancelled, it fails like any other first spawn,
 * the connection manager logs it as an error and kicks the player with {@code synaxis:internal-error}.
 */
public class AddEntityToInstanceEvent implements InstanceEvent, EntityEvent, CancellableEvent {

    private final Instance instance;
    private final Entity entity;

    private boolean cancelled;

    public AddEntityToInstanceEvent(Instance instance, Entity entity) {
        this.instance = instance;
        this.entity = entity;
    }

    @Override
    public Instance getInstance() {
        return instance;
    }

    /**
     * Entity being added.
     *
     * @return the entity being added
     */
    @Override
    public Entity getEntity() {
        return entity;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /** The failure of a change of instance a listener of {@link AddEntityToInstanceEvent} cancelled. */
    @SuppressWarnings("serial") // never serialized
    public static final class CancelledException extends IllegalStateException {
        public CancelledException() {
            super("AddEntityToInstanceEvent has been cancelled");
        }
    }
}
