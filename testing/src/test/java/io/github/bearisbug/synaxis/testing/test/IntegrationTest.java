package io.github.bearisbug.synaxis.testing.test;

import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@EnvTest
public class IntegrationTest {

    @Test
    void testEnv(Env env) {
        Assertions.assertNotNull(env);
        Assertions.assertNotNull(env.process());
    }

    @Test
    void insideTest(Env env) {
        Assertions.assertTrue(env.process().config().insideTest());
    }
}
