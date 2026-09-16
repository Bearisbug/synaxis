package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The server application: assembly, plugin host and console (API-M2-010).
 * <p>
 * The startup order below is part of the contract and cannot be rearranged. Enabling the plugins
 * has to happen before the port is bound, because the engine demands a spawning instance as soon as
 * a connection reaches the configuration phase; and it has to be synchronous on this thread,
 * because the scheduler is only advanced from inside a tick and the tick thread does not exist yet.
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static final String BIND_ADDRESS = "0.0.0.0";
    private static final String PORT_PROPERTY = "synaxis.app.port";
    private static final String PLUGINS_PROPERTY = "synaxis.app.plugins";
    private static final int DEFAULT_PORT = 25565;

    private Main() {
    }

    public static void main(String[] args) {
        try {
            start();
        } catch (IllegalArgumentException e) {
            // A refused launch argument or a refused descriptor is a decision, not a crash: it gets
            // the same ERROR line and non zero exit as the missing admission provider below,
            // rather than a bare stack trace on stderr.
            LOGGER.error("Refusing to start: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void start() {
        // (1) Process level configuration. The engine reads its own synaxis.* properties while the
        // process is being created, which is why this comes before init.
        final int port = port();
        final Set<String> selectedPlugins = selectedPlugins();

        // (2) The engine facade, not a bare ServerProcess: MinecraftServer#start is the only public
        // entry point doing bind, dispatcher and tick thread in the right order.
        final MinecraftServer server = MinecraftServer.init(new Auth.Offline());
        final ServerProcess process = server.process();

        // Boot self-check: the effective settings go in the log, so a launch argument that did not
        // land is visible here instead of at the first symptom.
        LOGGER.info("Effective {}", process.config());
        LOGGER.info("Effective {}", JvmFlags.describe());

        // (3) The single shallow DimensionType arrives with REQ-M2-011; it must be registered here,
        // before step (6) freezes the registries.

        // (4) Plugins, on this thread. A plugin builds its match here, before anything is bound.
        final PluginHost host = new PluginHost(process, Path.of("plugins"), new EventLedger(Path.of("logs")));
        host.enable(PluginHost.discover(), selectedPlugins);

        // (5) Without an admission provider no connection could be let anywhere, so refuse to open
        // the port at all rather than let every player hit an internal error.
        if (!host.hasAdmissionProvider()) {
            LOGGER.error("No plugin registered an admission provider, nothing would know where to put a player. "
                    + "Not binding {}:{}.", BIND_ADDRESS, port);
            System.exit(1);
        }

        // The only place the plugins are disabled, console and SIGINT alike: the engine drains its
        // shutdown tasks once the tick threads are joined and before it shuts anything else down, so
        // registering here gets onDisable the same quiet process on both paths.
        process.scheduler().buildShutdownTask(host::disableAll);

        // (6) bind, freeze the registries, accept, start the dispatcher and the tick thread.
        server.start(BIND_ADDRESS, port);

        // (7) Printed after all three, so "the ready log is out" means the process is ticking.
        LOGGER.info("synaxis server-app listening on {}:{}", BIND_ADDRESS, port);

        // Not a daemon: the shutdown sequence runs on this thread, and the JVM must not
        // exit out from under it once the tick threads are gone.
        Thread.ofPlatform().name("server-app-console").start(() -> readConsole(process));
    }

    /**
     * @return the port to bind, {@value #DEFAULT_PORT} when the property is not set
     * @throws IllegalArgumentException if the property is set to something that is not a number
     */
    static int port() {
        final String property = System.getProperty(PORT_PROPERTY);
        if (property == null) return DEFAULT_PORT;
        try {
            return Integer.parseInt(property.strip());
        } catch (NumberFormatException _) {
            // Integer.getInteger would fall back to the default here, and the process would come up
            // on a port nobody asked for (docs/TEST.md §3 reads this one back as a precondition).
            throw new IllegalArgumentException(PORT_PROPERTY + "=" + property + " is not a port number");
        }
    }

    static Set<String> selectedPlugins() {
        final String property = System.getProperty(PLUGINS_PROPERTY, "");
        return Arrays.stream(property.split(","))
                .map(String::strip)
                .filter(id -> !id.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void readConsole(ServerProcess process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!"stop".equals(line.strip())) continue;
                LOGGER.info("Console requested a shutdown.");
                process.stop();
                return;
            }
        } catch (IOException e) {
            LOGGER.error("Console reader stopped", e);
        }
    }
}
