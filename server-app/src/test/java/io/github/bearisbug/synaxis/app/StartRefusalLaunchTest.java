package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.Launched;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the log of a refused start says, in a JVM of its own ({@code docs/DESIGN-PROD.md} {@code API-PROD-023},
 * {@code docs/DESIGN-M2.md} {@code API-M2-010} step 5): the configuration file a refused key was read from comes
 * first, a refusal without a cause is one line without a stack, and a launch whose plugin failed to enable names the
 * plugin and the failure where the refusal is summed up.
 */
public class StartRefusalLaunchTest {

    private static final String SERVICES = "META-INF/services/" + SynaxisPlugin.class.getName();

    @Test
    public void aRefusedKeyIsOneLineAfterTheFileItWasReadFrom(@TempDir Path directory) throws Exception {
        ConfigFileTest.write(directory.resolve(ConfigFile.DEFAULT_FILE), "synaxis.app.log=stdout\nsynaxis.app.auth=bogus\n");
        try (Launched main = Launched.start(directory, Main.class, "-Dsynaxis.app.port=0",
                "-Dsynaxis.app.metrics.bind=127.0.0.1:0")) {
            assertEquals(1, main.awaitExit());
            final List<String> lines = main.output();
            final int file = indexOf(lines, "Effective config file: path="
                    + directory.toRealPath().resolve(ConfigFile.DEFAULT_FILE));
            final int refused = indexOf(lines, "Refusing to start: synaxis.app.auth=bogus is not a login mode");
            assertTrue(file >= 0 && file < refused, "the file comes before the refusal: " + main.log());
            assertTrue(refused == lines.size() - 1 || !(lines.get(refused + 1).startsWith("\tat ")
                    || lines.get(refused + 1).startsWith("java.lang.")), "no stack: " + main.log());
        }
    }

    @Test
    public void aPluginThatFailedToEnableIsNamedWhereTheRefusalIsSummedUp(@TempDir Path directory) throws Exception {
        final Path classes = directory.resolve("classes");
        Files.createDirectories(classes.resolve(SERVICES).getParent());
        Files.writeString(classes.resolve(SERVICES), FailingPlugin.class.getName() + "\n");
        final String classPath = System.getProperty("java.class.path") + File.pathSeparator + classes;
        try (Launched main = Launched.start(directory, classPath, Main.class, "-Dsynaxis.app.log=stdout",
                "-Dsynaxis.app.auth=offline", "-Dsynaxis.app.port=0", "-Dsynaxis.app.metrics.bind=127.0.0.1:0",
                "-Dsynaxis.app.plugins=" + FailingPlugin.ID)) {
            assertEquals(1, main.awaitExit());
            final List<String> lines = main.output();
            final int named = indexOf(lines, "Plugin " + FailingPlugin.ID + " failed to enable: "
                    + "java.lang.IllegalArgumentException: " + FailingPlugin.FAILURE + " (stack above)");
            final int summed = indexOf(lines, "No admission provider:");
            assertTrue(named >= 0 && named < summed, "the plugin is named before the halves it left: " + main.log());
            assertFalse(main.log().contains("synaxis server-app listening on"), main.log());
        }
    }

    private static int indexOf(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(text)) return i;
        return -1;
    }

    /** A plugin whose settings are wrong, the way a plugin reading its own configuration fails in onEnable. */
    public static final class FailingPlugin implements SynaxisPlugin {
        static final String ID = "failing";
        static final String FAILURE = "Property 'synaxis.failing.size' is not from 1 to 8: 9";

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(ID, "1.0.0", PluginApi.API_VERSION);
        }

        @Override
        public void onEnable(PluginContext context) {
            throw new IllegalArgumentException(FAILURE);
        }
    }
}
