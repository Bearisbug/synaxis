package io.github.bearisbug.synaxis.thread;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class AcquirableLocalsIntegrationTest {
    @Test
    public void empty(Env env) {
        assertEquals(0, Acquirable.localElements().count());
    }

    @Test
    public void localTest(Env env) {
        var instance = env.createFlatInstance();
        var zombie = new Entity(EntityType.ZOMBIE) {
            @Override
            public void tick(long time) {
                super.tick(time);
                assertEquals(Set.of(this), Acquirable.localElements()
                        .filter(Entity.class::isInstance)
                        .collect(Collectors.toUnmodifiableSet()));
            }
        };
        zombie.setInstance(instance, new Pos(1, 41, 1)).join();
        env.tick();
    }
}
