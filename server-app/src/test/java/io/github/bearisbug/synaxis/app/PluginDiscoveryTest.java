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
 * does, naming the class it is missing or its own class, rather than ending the startup thread with an error.
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

    /**
     * {@code ServiceLoader} looks a provider class up before it wraps any failure in a
     * {@link ServiceConfigurationError}: a provider whose superclass is in a jar left off the classpath fails with the
     * bare {@link NoClassDefFoundError}.
     */
    @Test
    void aPluginExtendingAClassOffTheClasspathRefusesTheStartNamingThatClass(@TempDir Path directory)
            throws IOException {
        final Path services = directory.resolve("META-INF/services/" + SynaxisPlugin.class.getName());
        Files.createDirectories(services.getParent());
        Files.writeString(services, OrphanPlugin.class.getName() + "\n");
        final Thread thread = Thread.currentThread();
        final ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader loader = new LoaderWithoutTheBase(directory, PluginDiscoveryTest.class.getClassLoader())) {
            thread.setContextClassLoader(loader);
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, PluginHost::discover);
            assertTrue(refused.getMessage().contains(MissingBase.class.getName().replace('.', '/')), refused.getMessage());
            assertInstanceOf(NoClassDefFoundError.class, refused.getCause());
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

    /** The superclass a plugin jar takes from a jar that is left off the classpath. */
    public abstract static class MissingBase implements SynaxisPlugin {
    }

    /** A plugin whose superclass its loader cannot find. */
    public static final class OrphanPlugin extends MissingBase {
        @Override
        public PluginDescriptor descriptor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onEnable(PluginContext context) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Defines {@link OrphanPlugin} itself from the test's class file and finds no {@link MissingBase}, as the
     * classpath of a plugin jar without the jar it extends a class of.
     */
    private static final class LoaderWithoutTheBase extends URLClassLoader {

        LoaderWithoutTheBase(Path services, ClassLoader parent) throws IOException {
            super(new URL[]{services.toUri().toURL()}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(MissingBase.class.getName())) throw new ClassNotFoundException(name);
            if (!name.equals(OrphanPlugin.class.getName())) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                final Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                try (var in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (in == null) throw new ClassNotFoundException(name);
                    final byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }
    }
}
