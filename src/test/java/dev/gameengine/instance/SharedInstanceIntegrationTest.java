package dev.gameengine.instance;

import net.kyori.adventure.key.Key;
import dev.gameengine.world.DimensionType;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class SharedInstanceIntegrationTest {

    // A shared instance shows the same world to the client as its container, including its name:
    // a mismatch makes Player#setInstance believe it is a dimension change
    @Test
    public void keepsTheContainerDimensionName(Env env) {
        var container = new InstanceContainer(env.process(), UUID.randomUUID(),
                DimensionType.OVERWORLD, null, Key.key("gameengine:lobby"));
        var shared = new SharedInstance(UUID.randomUUID(), container);
        assertEquals(container.getDimensionName(), shared.getDimensionName());
    }
}
