package net.minestom.server.entity;

import net.minestom.server.coordinate.Pos;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class ManualViewerLeakIntegrationTest {

    @Test
    public void removedPlayerIsDroppedFromManualViewers(Env env) {
        var instance = env.createFlatInstance();
        var hologram = new Entity(EntityType.ARMOR_STAND);
        hologram.setInstance(instance, new Pos(0, 42, 0)).join();
        var player = env.createPlayer(instance, new Pos(0, 42, 5000));

        assertTrue(hologram.addViewer(player));
        assertFalse(hologram.hasPredictableViewers());

        player.remove();
        assertTrue(hologram.hasPredictableViewers(), "manual viewer set still holds the removed player");
        assertFalse(hologram.removeViewer(player), "removed player is still registered as a manual viewer");
    }
}
