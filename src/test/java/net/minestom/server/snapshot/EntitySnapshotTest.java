package net.minestom.server.snapshot;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class EntitySnapshotTest {

    @Test
    public void snapshotEntityWithoutInstance() {
        // ServerSnapshot.update() may run while an entity is being removed, snapshotting an entity
        // that has neither instance nor chunk must not throw
        var _ = MinecraftServer.updateProcess();
        Entity entity = new Entity(EntityType.ZOMBIE);
        EntitySnapshot snapshot = assertDoesNotThrow(() -> SnapshotUpdater.<EntitySnapshot>update(entity));
        assertNotNull(snapshot);
        assertNull(snapshot.instance());
    }
}
