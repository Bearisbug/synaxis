package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.SessionServer;
import io.github.bearisbug.synaxis.Watchdog;
import io.github.bearisbug.synaxis.game.protocol.ProtocolSchema;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The server application: assembly, plugin host and console (API-M2-010).
 * <p>
 * The startup order below is part of the contract and cannot be rearranged. Enabling the plugins
 * has to happen before the port is bound, because the engine demands a spawning instance as soon as
 * a connection reaches the configuration phase; and it has to be synchronous on this thread,
 * because the scheduler is only advanced from inside a tick and the tick thread does not exist yet.
 * <p>
 * What the plugins left behind decides how the process serves: with gameplay it serves the match
 * they built, without any it serves the core world of {@link CoreMode}.
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static final String BIND_ADDRESS = "0.0.0.0";
    private static final String PORT_PROPERTY = "synaxis.app.port";
    private static final String PLUGINS_PROPERTY = "synaxis.app.plugins";
    private static final int DEFAULT_PORT = 25565;
    private static final String SHARD_ID_PROPERTY = "synaxis.app.shard-id";
    private static final String DEFAULT_SHARD_ID = "local";
    /** A shard id ends up in file names, so it is kept to characters that cannot leave the directory. */
    private static final Pattern SHARD_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final String LEDGER_MAX_BYTES_PROPERTY = "synaxis.app.ledger-max-bytes-per-day";
    private static final String AUTH_PROPERTY = "synaxis.app.auth";
    private static final String SESSION_SERVER_PROPERTY = "synaxis.app.auth.session-server";

    /**
     * The world height of §9, and the only one M2 has: the single shallow dimension type is built
     * from it, and a map preset taller than this is refused by the match that submits it.
     */
    private static final int MATCH_WORLD_HEIGHT = 64;

    /**
     * The process wide half of the view range contract of {@code docs/DESIGN-M2.md} §15: the RTS
     * camera looks at far more of the world than a first person player does, and the engine
     * defaults are nowhere near it. Two of the three freeze early — the entity view distance is
     * baked into the entity tracker when an instance is constructed, and the whole configuration is
     * read once while the process is created — so they are applied here, before anything else.
     * <p>
     * The chunk update limiter is off rather than tuned: it swallows a chunk update for a chunk it
     * has seen in its last five calls, which is exactly what pushing an RTS camera back and forth
     * across a chunk border does (see {@code ADR-M2-013}).
     * <p>
     * A value the operator passed on the command line wins, so that one of them can still be taken
     * out of the picture while diagnosing.
     */
    private static final Map<String, String> VIEW_RANGE_PROPERTIES = Map.of(
            "synaxis.entity-view-distance", "8",
            "synaxis.chunk-view-distance", "10",
            "synaxis.player.chunk-update-limiter-history-size", "0");

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
        final String shardId = shardId();
        final long ledgerMaxBytesPerDay = ledgerMaxBytesPerDay();
        final Set<String> selectedPlugins = selectedPlugins();
        final Auth auth = auth();
        final CoreMode.Setting coreMode = CoreMode.setting();
        applyViewRangeDefaults();

        // (2) The engine facade, not a bare ServerProcess: MinecraftServer#start is the only public
        // entry point doing bind, dispatcher and tick thread in the right order.
        final MinecraftServer server = MinecraftServer.init(auth);
        final ServerProcess process = server.process();

        // Boot self-check: the effective settings go in the log, so a launch argument that did not
        // land is visible here instead of at the first symptom.
        LOGGER.info("Effective {}", process.config());
        LOGGER.info("Effective {}", JvmFlags.describe());
        logAuth(auth);

        // (3) The one shallow dimension type every match is built on. It has to be registered here:
        // step (6) freezes the registries, and a dimension type registered after the port is open
        // would shift the dimension indices of the players already in game (§11.3).
        final RegistryKey<DimensionType> dimensionType = registerMatchDimensionType(process);

        // (4) Plugins, on this thread. A plugin builds its match here, before anything is bound.
        // An operator who asked for the core mode gets no plugin at all: that is the only way the
        // core mode is deterministic while a gameplay plugin sits on the classpath.
        final EventLedger ledger = new EventLedger(Path.of("logs"), shardId, ledgerMaxBytesPerDay);
        ledger.start();
        final PluginHost host = new PluginHost(process, Path.of("plugins"), ledger);
        final CrashReports crashReports = crashReports(process, host, shardId, Path.of("crash-reports"));
        installExceptionHandler(process, crashReports);
        final Watchdog watchdog = startWatchdog(process, crashReports);
        if (coreMode != CoreMode.Setting.ON) host.enable(PluginHost.discover(), selectedPlugins);

        // (5) Which mode this process serves in. Gameplay needs both halves — an admission provider
        // to say where a player goes and a running match to put them in — and a launch with no
        // plugin to provide them serves the core's own world instead. A plugin that took part and
        // left one half or neither behind is a plugin that came up wrong: the port stays closed
        // rather than letting every player hit an internal error, or hiding the failure behind a
        // world nobody can play in.
        final int runningMatches = runningMatches(process);
        switch (CoreMode.decide(coreMode, host.attemptedPluginCount(),
                host.hasAdmissionProvider(), runningMatches)) {
            case CORE -> CoreMode.start(process, host, dimensionType, coreMode);
            case GAMEPLAY -> {
                // Only a process that serves a game demands the mod that renders it; the core mode
                // answers a handshake but never waits for one (REQ-M2-008, API-M2-010 step 5).
                host.requireHandshake();
                LOGGER.info("Gameplay mode: the running match and the admission provider come from "
                        + "plugin_ids={}, and a client has {} s to send C2S_HELLO on {} in the {} phase "
                        + "(schema_version={})", host.enabledPluginIds(), HandshakeGate.BUDGET.toSeconds(),
                        HandshakeGate.CHANNEL, HandshakeGate.phase().name().toLowerCase(Locale.ROOT),
                        ProtocolSchema.SCHEMA_VERSION);
            }
            case REFUSED -> refuseToServe(host, ledger, runningMatches, port);
        }

        // The only place the plugins are disabled, console and SIGINT alike: the engine drains its
        // shutdown tasks once the tick threads are joined and before it shuts anything else down, so
        // registering here gets onDisable the same quiet process on both paths.
        process.scheduler().buildShutdownTask(host::disableAll);
        // After the plugins, so that what they record in onDisable is on disk (API-PROD-014)
        process.scheduler().buildShutdownTask(ledger::close);
        // Last: a tick still in flight keeps being watched after this, see Watchdog#stop
        process.scheduler().buildShutdownTask(watchdog::stop);

        // (6) bind, freeze the registries, accept, start the dispatcher and the tick thread.
        server.start(BIND_ADDRESS, port);

        // (7) Printed after all three, so "the ready log is out" means the process is ticking.
        LOGGER.info("synaxis server-app listening on {}:{}", BIND_ADDRESS, port);

        // Not a daemon: the shutdown sequence runs on this thread, and the JVM must not
        // exit out from under it once the tick threads are gone.
        Thread.ofPlatform().name("server-app-console").start(() -> readConsole(process));
    }

    /**
     * The crash report writer of the process ({@code docs/DESIGN-PROD.md} {@code API-PROD-013}),
     * shared by every fatal path: the exception handler below and the tick watchdog.
     *
     * @param process   the engine process, whose settings go into a report
     * @param host      the plugin host, whose plugins go into a report
     * @param shardId   the shard id of the file names
     * @param directory where the reports go
     * @return the writer
     */
    static CrashReports crashReports(ServerProcess process, PluginHost host, String shardId, Path directory) {
        return new CrashReports(directory, shardId, Clock.system(ZoneId.systemDefault()),
                () -> CrashReports.describe(List.of(process.config().toString(), JvmFlags.describe()),
                        System.getProperties(), System.getenv(), ManagementFactory.getRuntimeMXBean().getInputArguments()),
                host::enabledPluginVersions);
    }

    /**
     * Sends what the engine could not handle, and what kills a thread, to the host's handler
     * ({@code API-PROD-013}). The tick number in its lines is counted here, at the start of every
     * tick of the scheduler, so that it reads the same on every thread.
     *
     * @param process      the engine process
     * @param crashReports the writer of the fatal branch
     * @return the handler, which the JVM now also calls for every thread that dies of a throwable
     */
    static HostExceptionHandler installExceptionHandler(ServerProcess process, CrashReports crashReports) {
        final AtomicLong tick = new AtomicLong();
        process.scheduler().scheduleTask(() -> {
            tick.incrementAndGet();
            return TaskSchedule.nextTick();
        }, TaskSchedule.nextTick());
        final HostExceptionHandler handler = new HostExceptionHandler(crashReports, tick::get);
        process.exception().setExceptionHandler(handler);
        Thread.setDefaultUncaughtExceptionHandler(handler::handleUncaught);
        return handler;
    }

    /**
     * Starts the tick watchdog of the process ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-014}),
     * before the first tick. A process it judges dead gets a crash report, then is halted.
     *
     * @param process      the engine process
     * @param crashReports the writer of the report
     * @return the watchdog, for the stop sequence to stop
     */
    static Watchdog startWatchdog(ServerProcess process, CrashReports crashReports) {
        final Watchdog watchdog = process.watchdog();
        watchdog.start(stuckMillis -> crashReports.write(
                "tick watchdog: the tick has been stuck for " + stuckMillis + " ms, the process is judged dead", null));
        return watchdog;
    }

    /**
     * Registers the one shallow dimension type of the process, before anything is bound.
     *
     * @param process the engine process
     * @return its key, which the core world and every match are built on
     */
    static RegistryKey<DimensionType> registerMatchDimensionType(ServerProcess process) {
        return process.dimensionType().register(Key.key(PluginApi.MATCH_DIMENSION_TYPE),
                DimensionType.builder().minY(0).height(MATCH_WORLD_HEIGHT).logicalHeight(MATCH_WORLD_HEIGHT).build());
    }

    /**
     * Reports what a gameplay launch is missing and exits without binding. Both halves are reported,
     * because which one is missing says what went wrong with the plugin.
     *
     * @param host    the plugin host
     * @param ledger  the event ledger, drained before the exit so the plugin events stay
     * @param matches how many matches are running, the count the mode was decided on
     * @param port    the port that is not going to be bound
     */
    private static void refuseToServe(PluginHost host, EventLedger ledger, int matches, int port) {
        if (!host.hasAdmissionProvider())
            LOGGER.error("No admission provider: no plugin registered one, so nothing would know where to put "
                    + "a player.");
        if (matches != 1)
            LOGGER.error("No running match: a plugin has to build one and register it before the port opens, "
                    + "and M2 expects exactly one (found {}).", matches);
        LOGGER.error("Not binding {}:{}.", BIND_ADDRESS, port);
        ledger.close();
        System.exit(1);
    }

    /**
     * The registered instances, which a match only becomes once its whole map is generated
     * (§11.3 {@code loading → running}): that makes the instance registry the host side view of
     * "the match is running", without the host having to know what a match is.
     *
     * @param process the engine process
     * @return the number of running matches
     */
    private static int runningMatches(ServerProcess process) {
        return process.instance().getInstances().size();
    }

    static void applyViewRangeDefaults() {
        for (Map.Entry<String, String> property : VIEW_RANGE_PROPERTIES.entrySet()) {
            if (System.getProperty(property.getKey()) == null)
                System.setProperty(property.getKey(), property.getValue());
        }
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

    /**
     * The login mode of {@code docs/DESIGN-PROD.md} {@code API-PROD-001}: {@code online} unless the
     * launch says otherwise, so a deployment that forgot the argument fails to let anyone in rather
     * than letting everyone in under any name.
     *
     * @return the auth of the process
     * @throws IllegalArgumentException if the mode is not one of the three, or the session server
     *                                  override is not an http or https URL
     */
    static Auth auth() {
        final String mode = System.getProperty(AUTH_PROPERTY, "online").strip();
        return switch (mode) {
            case "online" -> {
                final String override = System.getProperty(SESSION_SERVER_PROPERTY);
                yield new Auth.Online(override == null ? SessionServer.mojang() : SessionServer.at(sessionServer(override)));
            }
            case "offline" -> new Auth.Offline(false);
            case "offline-encrypted" -> new Auth.Offline(true);
            default -> throw new IllegalArgumentException(AUTH_PROPERTY + "=" + mode
                    + " is not a login mode: online, offline or offline-encrypted");
        };
    }

    private static URI sessionServer(String property) {
        final URI uri;
        try {
            uri = URI.create(property.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(SESSION_SERVER_PROPERTY + "=" + property + " is not a URL", e);
        }
        if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())))
            throw new IllegalArgumentException(SESSION_SERVER_PROPERTY + "=" + property + " is not an http or https URL");
        return uri;
    }

    /**
     * The login mode goes in the boot self-check. A process verifying against anything but Mojang
     * lets in whoever that server vouches for, which is what CI and load tests want and a
     * production deployment must never do, so it is a warning.
     */
    private static void logAuth(Auth auth) {
        switch (auth) {
            case Auth.Online online when online.sessionServer().isMojang() ->
                    LOGGER.info("Effective auth: mode=online session_server={}", online.sessionServer().hasJoinedUrl());
            case Auth.Online online -> LOGGER.warn("Effective auth: mode=online session_server={} is not Mojang's "
                    + "session server; a login is let in on whatever it answers, which is for CI and load "
                    + "tests only", online.sessionServer().hasJoinedUrl());
            case Auth.Offline offline -> LOGGER.info("Effective auth: mode={}",
                    offline.encrypt() ? "offline-encrypted" : "offline");
            default -> LOGGER.info("Effective auth: {}", auth);
        }
    }

    /**
     * @return the shard id of the ledger and crash report file names, {@value #DEFAULT_SHARD_ID}
     *         when the property is not set
     * @throws IllegalArgumentException if it holds a character a file name must not carry
     */
    static String shardId() {
        final String shardId = System.getProperty(SHARD_ID_PROPERTY, DEFAULT_SHARD_ID).strip();
        if (!SHARD_ID.matcher(shardId).matches())
            throw new IllegalArgumentException(SHARD_ID_PROPERTY + "=" + shardId + " is not a shard id: letters, "
                    + "digits, '.', '_' and '-' only, starting with a letter or a digit");
        return shardId;
    }

    /**
     * @return the daily size cap of the event ledger in bytes, 1 GiB when the property is not set
     * @throws IllegalArgumentException if the property is not a positive number
     */
    static long ledgerMaxBytesPerDay() {
        final String property = System.getProperty(LEDGER_MAX_BYTES_PROPERTY);
        if (property == null) return EventLedger.DEFAULT_MAX_BYTES_PER_DAY;
        try {
            final long bytes = Long.parseLong(property.strip());
            if (bytes > 0) return bytes;
        } catch (NumberFormatException _) {
            // reported below, like a negative value
        }
        throw new IllegalArgumentException(LEDGER_MAX_BYTES_PROPERTY + "=" + property + " is not a positive number of bytes");
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
