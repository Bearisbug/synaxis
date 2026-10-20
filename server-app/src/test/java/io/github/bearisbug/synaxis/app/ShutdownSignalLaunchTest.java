package io.github.bearisbug.synaxis.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SIGTERM while a console {@code stop 30} counts down, in a JVM of its own
 * ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-008}, §11.4 {@code gate_closed → saving} on a
 * signal; {@code docs/DESIGN.md} {@code API-003}). The JVM halts once its shutdown hooks return,
 * whatever thread still runs, so the hook cuts the countdown short and waits for the sequence the
 * console started; a hook that returned at once let the process die inside the countdown, with no
 * save, no kick and no {@code onDisable}.
 */
public class ShutdownSignalLaunchTest {

    /** Far under the 30 s countdown: with nobody online the sequence takes about half a second. */
    private static final long EXIT_WITHIN_SECONDS = 10;
    /** 128 + SIGTERM, what a JVM ended by the signal exits with. */
    private static final int SIGTERM_EXIT_CODE = 143;

    @Test
    public void aSignalDuringAConsoleCountdownCutsItAndWaitsForTheSequence(@TempDir Path directory) throws Exception {
        try (ConfigFileLaunchTest.Launched server = ConfigFileLaunchTest.Launched.start(directory, Main.class,
                "-Dsynaxis.app.log=stdout", "-Dsynaxis.app.auth=offline", "-Dsynaxis.app.core-mode=on",
                "-Dsynaxis.app.port=0", "-Dsynaxis.app.metrics.bind=127.0.0.1:0")) {
            server.awaitLine("synaxis server-app listening on");
            final OutputStream console = server.process().getOutputStream();
            console.write("stop 30\n".getBytes(StandardCharsets.UTF_8));
            console.flush();
            server.awaitLine("The server stops in 30 s");

            final long start = System.nanoTime();
            // SIGTERM, as systemd or `kill <pid>` sends it. Through the handle: Process#destroy also
            // closes the pipes, and what the JVM logs after the signal would be lost to the test
            assertTrue(server.process().toHandle().destroy(), "SIGTERM sent");
            final boolean exited = server.process().waitFor(EXIT_WITHIN_SECONDS, TimeUnit.SECONDS);
            final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            server.reader().join(TimeUnit.SECONDS.toMillis(10));
            final String log = server.log();

            assertTrue(exited, "the signal cut the 30 s countdown:\n" + log);
            assertEquals(SIGTERM_EXIT_CODE, server.process().exitValue(), log);
            assertTrue(log.contains("synaxis server stopped successfully."),
                    "the sequence the console started ran to its end before the JVM halted ("
                            + tookMillis + " ms):\n" + log);
            final String saved = ShutdownBeforeTheWorldLaunchTest.ledgerLine(directory, "\"shutdown_saved\"");
            assertTrue(saved.contains("\"timed_out\":\"false\""), saved);
        }
    }
}
