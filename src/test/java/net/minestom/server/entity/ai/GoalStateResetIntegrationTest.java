package net.minestom.server.entity.ai;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

@EnvTest
public class GoalStateResetIntegrationTest {

    @Test
    public void resettingThePathClearsTheGoalPosition(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        var creature = new EntityCreature(EntityType.ZOMBIE);
        creature.setInstance(instance, new Pos(0, 40, 0)).join();

        var navigator = creature.getNavigator();
        navigator.setPathTo(new Pos(5, 40, 5));
        navigator.setPathTo(null); // Documented as "reset the pathfinder"

        assertNull(navigator.getPathPosition(), "the goal position survived a pathfinder reset");
    }

    @Test
    public void removedTargetIsForgotten(Env env) {
        var instance = env.createFlatInstance();
        var creature = new EntityCreature(EntityType.ZOMBIE);
        creature.setInstance(instance, new Pos(0, 42, 0)).join();
        var prey = new EntityCreature(EntityType.ZOMBIE);
        prey.setInstance(instance, new Pos(0, 42, 2)).join();

        creature.setTarget(prey);
        prey.remove();

        assertNull(creature.getTarget(), "a removed target is kept alive by the creature");
    }
}
