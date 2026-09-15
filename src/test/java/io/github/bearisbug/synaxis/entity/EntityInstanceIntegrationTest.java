package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class EntityInstanceIntegrationTest {

    @Test
    public void entityJoin(Env env) {
        var instance = env.createFlatInstance();
        var entity = new Entity(EntityTypes.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        assertEquals(instance, entity.getInstance());
        assertEquals(new Pos(0, 42, 0), entity.getPosition());
    }

    @Test
    public void playerJoin(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        assertEquals(instance, player.getInstance());
        assertEquals(new Pos(0, 42, 0), player.getPosition());
    }

    @Test
    public void playerSwitch(Env env) {
        var instance = env.createFlatInstance();
        var instance2 = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        assertEquals(instance, player.getInstance());
        // #join may cause the thread to hang as scheduled for the next tick when initially in a pool
        Assertions.assertTimeout(Duration.ofSeconds(15), () -> player.setInstance(instance2).join());
        assertEquals(instance2, player.getInstance());
    }
}
