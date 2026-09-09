package dev.gameengine.testing.test;

import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@EnvTest
public class IntegrationTest {

    @Test
    void testEnv(Env env) {
        Assertions.assertNotNull(env);
        Assertions.assertNotNull(env.process());
    }
}
