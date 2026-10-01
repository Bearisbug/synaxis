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
 * A boolean key that is neither {@code true} nor {@code false} refuses the start of a real JVM
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-023}, §19 the scenario of boolean keys): on the
 * command line of {@code Main}, and with a trailing blank in the {@code synaxis.properties} a shard
 * reads. Each JVM gets a free game port, so a launcher that let the value through comes up there
 * and the test sees it listening.
 */
public class BooleanKeysLaunchTest {

    private static String[] launch(String... more) throws Exception {
        final String[] base = {"-Dsynaxis.app.log=stdout", "-Dsynaxis.app.auth=offline", "-Dsynaxis.app.core-mode=on",
                "-Dsynaxis.app.port=" + freePort(), "-Dsynaxis.app.metrics.bind=127.0.0.1:0"};
        final String[] all = new String[base.length + more.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return all;
    }

    private static void assertRefused(Launched launched, String refusal) throws InterruptedException {
        assertEquals(1, launched.awaitExit(), launched.log());
        final String log = launched.log();
        assertTrue(log.contains("Refusing to start: " + refusal), log);
        assertFalse(log.contains("listening on"), log);
    }

    @Test
    public void anotherWordOnTheCommandLineRefusesMain(@TempDir Path directory) throws Exception {
        try (Launched main = Launched.start(directory, Main.class, launch("-Dsynaxis.reject-malformed-packet=yes"))) {
            assertRefused(main, "Property 'synaxis.reject-malformed-packet' is not true or false: \"yes\"");
        }
    }

    @Test
    public void aTrailingBlankInTheFileRefusesAShard(@TempDir Path directory) throws Exception {
        ConfigFileTest.write(directory.resolve(ConfigFile.DEFAULT_FILE), "synaxis.enforce-entity-interaction-range=true \n");
        try (Launched shard = Launched.start(directory, ShardMain.class, launch())) {
            assertRefused(shard, "Property 'synaxis.enforce-entity-interaction-range' is not true or false: \"true \"");
        }
    }
}
