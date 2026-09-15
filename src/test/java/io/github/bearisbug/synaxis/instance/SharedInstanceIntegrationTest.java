package io.github.bearisbug.synaxis.instance;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.world.DimensionType;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
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
                DimensionType.OVERWORLD, null, Key.key("synaxis:lobby"));
        var shared = new SharedInstance(UUID.randomUUID(), container);
        assertEquals(container.getDimensionName(), shared.getDimensionName());
    }
}
