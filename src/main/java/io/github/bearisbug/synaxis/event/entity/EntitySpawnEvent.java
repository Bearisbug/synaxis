package io.github.bearisbug.synaxis.event.entity;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;
import io.github.bearisbug.synaxis.instance.Instance;

/**
 * Called when a new instance is set for an entity.
 */
public class EntitySpawnEvent implements EntityInstanceEvent {

    private final Entity entity;
    private final Instance spawnInstance;

    public EntitySpawnEvent(Entity entity, Instance spawnInstance) {
        this.entity = entity;
        this.spawnInstance = spawnInstance;
    }

    /**
     * Gets the entity who spawned in the instance.
     *
     * @return the entity
     */
    @Override
    public Entity getEntity() {
        return entity;
    }

    /**
     * Gets the entity new instance.
     *
     * @return the instance
     */
    public Instance getSpawnInstance() {
        return spawnInstance;
    }

}
