package io.github.bearisbug.synaxis.testing.test;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClassEnvCleanupCheck implements AfterAllCallback {

    @Override
    public void afterAll(ExtensionContext context) {
        var env = ClassEnvCleanupIntegrationTest.classEnv;
        assertNotNull(env, "the class never received an Env");
        var alive = env.process().dispatcher().threads().stream().filter(Thread::isAlive).toList();
        assertTrue(alive.isEmpty(),
                "the Env injected into @BeforeAll was never cleaned up, tick threads still alive: " + alive);
    }
}
