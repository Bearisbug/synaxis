package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.Launched;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.freePort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A console {@code stop} ends the process with exit code 0 once the shutdown sequence has stopped,
 * in a JVM of its own ({@code docs/DESIGN-PROD.md} §11.4 {@code plugins_disabled → stopped}): a
 * thread a plugin left behind does not keep it alive. The JVM used to wait for every non daemon
 * thread, and a stopped shard whose plugin forgot its executor never exited, so its unit was never
 * restarted.
 */
public class ShutdownExitLaunchTest {

    private static final long EXIT_WITHIN_SECONDS = 20;

    /** A match and an admission provider, and a non daemon thread that never ends and is never stopped. */
    public static final class LingeringPlugin implements SynaxisPlugin {
        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor("lingering", "1.0.0", PluginApi.API_VERSION);
        }

        @Override
        public void onEnable(PluginContext context) {
            final Instance match = context.process().instance().createInstanceContainer();
            context.admission().register(_ -> new Admission.Accept(match, 1));
            Thread.ofPlatform().name("lingering").daemon(false).start(() -> {
                while (true) LockSupport.park();
            });
        }
    }

    @Test
    public void aStopEndsTheProcessWithZeroWhileAPluginThreadLingers(@TempDir Path directory) throws Exception {
        // The plugin is listed by a services file on a class path entry of this test alone
        final Path classes = directory.resolve("classes");
        Files.writeString(Files.createDirectories(classes.resolve("META-INF/services"))
                .resolve(SynaxisPlugin.class.getName()), LingeringPlugin.class.getName() + "\n");
        final String classPath = classes + File.pathSeparator + System.getProperty("java.class.path");

        try (Launched server = Launched.start(directory, classPath, Main.class, "-Dsynaxis.app.log=stdout",
                "-Dsynaxis.app.auth=offline", "-Dsynaxis.app.plugins=lingering", "-Dsynaxis.app.port=" + freePort(),
                "-Dsynaxis.app.metrics.bind=127.0.0.1:0")) {
            assertTrue(server.awaitLine("synaxis server-app listening on").contains("Gameplay mode"), server.log());
            final OutputStream console = server.process().getOutputStream();
            console.write("stop\n".getBytes(StandardCharsets.UTF_8));
            console.flush();

            final boolean exited = server.process().waitFor(EXIT_WITHIN_SECONDS, TimeUnit.SECONDS);
            server.reader().join(TimeUnit.SECONDS.toMillis(10));
            final String log = server.log();
            assertTrue(exited, "the process exited within " + EXIT_WITHIN_SECONDS + " s of the stop:\n" + log);
            assertEquals(0, server.process().exitValue(), log);
            assertTrue(log.contains("synaxis server stopped successfully."), log);
        }
    }
}
