package dev.gameengine.entity;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@EnvTest
public class NonPlayerViewerOptionIntegrationTest {

    @Test
    public void viewerOptionsOfANonPlayerAreNoOps(Env env) {
        var instance = env.createFlatInstance();
        var zombie = new Entity(EntityType.ZOMBIE);
        zombie.setInstance(instance, new Pos(0, 42, 0)).join();
        // A second auto-viewable entity nearby is what the update loop tries to hand to the null consumer
        var other = new Entity(EntityType.ZOMBIE);
        other.setInstance(instance, new Pos(1, 42, 1)).join();

        assertDoesNotThrow(() -> zombie.updateViewerRule());
        assertDoesNotThrow(() -> zombie.setAutoViewEntities(false));
        assertDoesNotThrow(() -> zombie.setAutoViewEntities(true));
    }
}
