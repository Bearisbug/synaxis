package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceConfigurationError;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plugin discovery ({@code docs/DESIGN-M2.md} {@code ADR-M2-006}): a provider on the classpath that cannot be built,
 * such as one whose jar misses a class or whose constructor throws, refuses the start the way a refused descriptor
 * does, naming its class, rather than ending the startup thread with an error.
 */
class PluginDiscoveryTest {

    @Test
    void aPluginThatCannotBeBuiltRefusesTheStartNamingItsClass(@TempDir Path directory) throws IOException {
        final Path services = directory.resolve("META-INF/services/" + SynaxisPlugin.class.getName());
        Files.createDirectories(services.getParent());
        Files.writeString(services, BrokenPlugin.class.getName() + "\n");
        final Thread thread = Thread.currentThread();
        final ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{directory.toUri().toURL()},
                PluginDiscoveryTest.class.getClassLoader())) {
            // ServiceLoader#load(Class), which discovery uses, reads the context class loader
            thread.setContextClassLoader(loader);
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, PluginHost::discover);
            assertTrue(refused.getMessage().contains(BrokenPlugin.class.getName()), refused.getMessage());
            assertInstanceOf(ServiceConfigurationError.class, refused.getCause());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** A plugin whose constructor throws. */
    public static final class BrokenPlugin implements SynaxisPlugin {
        public BrokenPlugin() {
            throw new IllegalStateException("cannot be built");
        }

        @Override
        public PluginDescriptor descriptor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onEnable(PluginContext context) {
            throw new UnsupportedOperationException();
        }
    }
}
