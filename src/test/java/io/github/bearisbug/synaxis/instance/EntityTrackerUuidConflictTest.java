package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class EntityTrackerUuidConflictTest {

    @Test
    public void rejectedDuplicateUuidLeavesNothingBehind() {
        final UUID uuid = UUID.randomUUID();
        var registered = new Entity(EntityType.ZOMBIE, uuid);
        var rejected = new Entity(EntityType.ZOMBIE, uuid);
        EntityTracker tracker = EntityTracker.newTracker(ServerConfig.defaults().entityViewDistance());
        tracker.register(registered, Vec.ZERO, EntityTracker.Target.ENTITIES, null);

        assertThrows(IllegalStateException.class,
                () -> tracker.register(rejected, Vec.ZERO, EntityTracker.Target.ENTITIES, null));
        assertNull(tracker.getEntityById(rejected.getEntityId()),
                "the rejected entity stayed half registered in the id table");

        tracker.unregister(rejected, EntityTracker.Target.ENTITIES, null);
        assertSame(registered, tracker.getEntityByUuid(uuid),
                "unregistering the rejected entity dropped the uuid index of the registered one");
        assertSame(registered, tracker.getEntityById(registered.getEntityId()));
    }
}
