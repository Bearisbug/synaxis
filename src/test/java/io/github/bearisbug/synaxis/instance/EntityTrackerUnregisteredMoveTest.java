package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EntityTrackerUnregisteredMoveTest {

    @Test
    public void movingAnUnregisteredEntityIsReportedAtMostOncePerInterval() {
        final EntityTrackerImpl tracker =
                (EntityTrackerImpl) EntityTracker.newTracker(ServerConfig.defaults().entityViewDistance());
        final Entity stranger = new Entity(EntityType.ZOMBIE);

        // The first attempt is the one that gets written, draining the pending counter
        tracker.move(stranger, Vec.ZERO, EntityTracker.Target.ENTITIES, null);
        assertEquals(0, tracker.unregisteredMoveAttempts.get(),
                "the first attempt was not reported");

        final int flood = 10_000;
        for (int i = 0; i < flood; i++) {
            tracker.move(stranger, new Vec(i, 0, 0), EntityTracker.Target.ENTITIES, null);
        }
        assertEquals(flood, tracker.unregisteredMoveAttempts.get(),
                "the report is written per packet, so a client controls how much is logged");
    }
}
