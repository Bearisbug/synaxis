package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.Launched;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.freePort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bind key that is not {@code host:port} refuses the start of a real JVM, the key named in the
 * one {@code Refusing to start} line ({@code docs/DESIGN-PROD.md} {@code API-PROD-015},
 * {@code API-PROD-022}, §19 the scenario of the bind keys). Each JVM gets a free game port, so a
 * launcher that let the value through comes up there and the test sees it listening.
 */
public class BindKeysLaunchTest {

    private static Launched launch(Path directory, String bind) throws Exception {
        return Launched.start(directory, Main.class, "-Dsynaxis.app.log=stdout", "-Dsynaxis.app.auth=offline",
                "-Dsynaxis.app.core-mode=on", "-Dsynaxis.app.port=" + freePort(),
                "-Dsynaxis.app.metrics.bind=127.0.0.1:0", bind);
    }

    private static void assertRefused(Launched launched, String refusal) throws InterruptedException {
        assertEquals(1, launched.awaitExit(), launched.log());
        final String log = launched.log();
        assertTrue(log.contains("Refusing to start: " + refusal), log);
        assertFalse(log.contains("listening on"), log);
    }

    @Test
    public void aUserInfoInTheManagementBindRefusesTheStart(@TempDir Path directory) throws Exception {
        final String bind = "127.0.0.1@0.0.0.0:" + freePort();
        try (Launched main = launch(directory, "-Dsynaxis.app.management.bind=" + bind)) {
            assertRefused(main, "synaxis.app.management.bind=" + bind + " is not host:port");
        }
    }

    @Test
    public void aQueryInTheMetricsBindRefusesTheStart(@TempDir Path directory) throws Exception {
        final String bind = "127.0.0.1:" + freePort() + "?x";
        // The later -D of the same key is the one the JVM keeps
        try (Launched main = launch(directory, "-Dsynaxis.app.metrics.bind=" + bind)) {
            assertRefused(main, "synaxis.app.metrics.bind=" + bind + " is not host:port");
        }
    }
}
