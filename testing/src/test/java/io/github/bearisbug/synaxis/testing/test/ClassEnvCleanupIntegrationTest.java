package io.github.bearisbug.synaxis.testing.test;

import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@link Env} injected into {@code @BeforeAll} is stored one level above the test method, out of
 * reach of the per-method cleanup. {@link ClassEnvCleanupCheck} asserts that it is stopped once the
 * class is over; it is registered first so that its callback runs after the fixture cleanup.
 */
@ExtendWith(ClassEnvCleanupCheck.class)
@EnvTest
public class ClassEnvCleanupIntegrationTest {

    static Env classEnv;

    @BeforeAll
    static void setup(Env env) {
        classEnv = env;
    }

    @Test
    void envIsSharedWithTheTestMethod(Env env) {
        assertNotNull(env);
        assertSame(classEnv, env);
        assertTrue(env.process().dispatcher().threads().stream().anyMatch(Thread::isAlive),
                "the fixture is expected to have running tick threads while the class runs");
    }
}
