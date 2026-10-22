package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.SessionServer;
import io.github.bearisbug.synaxis.Watchdog;
import io.github.bearisbug.synaxis.app.command.HostCommands;
import io.github.bearisbug.synaxis.app.command.Profiler;
import io.github.bearisbug.synaxis.app.command.TransferCommand;
import io.github.bearisbug.synaxis.app.management.ManagementMethods;
import io.github.bearisbug.synaxis.app.management.ManagementServer;
import io.github.bearisbug.synaxis.app.management.ManagementSettings;
import io.github.bearisbug.synaxis.app.metrics.HostMetrics;
import io.github.bearisbug.synaxis.app.moderation.HostModeration;
import io.github.bearisbug.synaxis.app.moderation.ModerationCommands;
import io.github.bearisbug.synaxis.app.moderation.ModerationStore;
import io.github.bearisbug.synaxis.app.permission.HostPermissions;
import io.github.bearisbug.synaxis.app.permission.RoleCommand;
import io.github.bearisbug.synaxis.app.permission.RoleStore;
import io.github.bearisbug.synaxis.app.play.DefaultPlay;
import io.github.bearisbug.synaxis.app.shard.ShardArrivals;
import io.github.bearisbug.synaxis.app.shard.ShardDirectory;
import io.github.bearisbug.synaxis.app.shard.ShardGates;
import io.github.bearisbug.synaxis.app.shard.ShardHeartbeat;
import io.github.bearisbug.synaxis.app.shard.ShardService;
import io.github.bearisbug.synaxis.app.shard.ShardServiceImpl;
import io.github.bearisbug.synaxis.app.shard.ShardSettings;
import io.github.bearisbug.synaxis.app.shard.ShardWorld;
import io.github.bearisbug.synaxis.app.shard.WorldFlagsImpl;
import io.github.bearisbug.synaxis.app.store.AuthMode;
import io.github.bearisbug.synaxis.app.store.Database;
import io.github.bearisbug.synaxis.app.store.Migrations;
import io.github.bearisbug.synaxis.app.store.PlayerStateSection;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.PositionRule;
import io.github.bearisbug.synaxis.app.store.PostgresBackend;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.app.store.ShardSession;
import io.github.bearisbug.synaxis.app.store.StoreHousekeeping;
import io.github.bearisbug.synaxis.app.store.StoreSettings;
import io.github.bearisbug.synaxis.app.transfer.TicketIssuer;
import io.github.bearisbug.synaxis.app.transfer.TicketVerifier;
import io.github.bearisbug.synaxis.app.transfer.TransferCooldowns;
import io.github.bearisbug.synaxis.app.transfer.TransferKeys;
import io.github.bearisbug.synaxis.app.transfer.TransferTicket;
import io.github.bearisbug.synaxis.app.transfer.TransferTickets;
import io.github.bearisbug.synaxis.command.CommandManager;
import io.github.bearisbug.synaxis.command.builder.CommandResult;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.game.protocol.ProtocolSchema;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
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

    /**
     * {@code synaxis.properties} goes into the system properties before anything reads one of them
     * ({@code docs/DESIGN-PROD.md} {@code API-PROD-023}): the logging setup reads
     * {@code synaxis.app.log} as {@link #LOGGER} is created, {@code JvmFlags} is read when the
     * engine first loads it, the rest from {@link #start(boolean)} on. So this field stays above the
     * logger; {@link #main} reports a refused file once the logging is up.
     */
    private static final ConfigFile.Outcome CONFIG_FILE = ConfigFile.applyToSystemProperties();

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
    static final String STORE_PROPERTY = "synaxis.app.store";
    private static final String WHITELIST_PROPERTY = "synaxis.app.whitelist";
    private static final String MAX_PLAYERS_PROPERTY = "synaxis.max-players";
    static final String STORE_MEMORY = "memory";
    static final String STORE_POSTGRES = "postgres";
    static final String MIGRATE_ONLY = "--migrate-only";
    static final String AUTH_MODE_ARGUMENT = "--auth-mode=";
    /** The exit code of a process the tick watchdog judged dead (§11.5). */
    private static final int DEATH_EXIT_CODE = 70;
    private static final long DEATH_POLL_MILLIS = 100;
    /** Set first thing when the tick watchdog judges the process dead, which then exits with 70, not 0. */
    private static final AtomicBoolean JUDGED_DEAD = new AtomicBoolean();

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

    /**
     * Returns what reading {@code synaxis.properties} came to; the first use of this class applies it.
     *
     * @return the outcome
     */
    static ConfigFile.Outcome configFile() {
        return CONFIG_FILE;
    }

    public static void main(String[] args) {
        main(args, false);
    }

    /**
     * Starts the server application; {@link ShardMain} starts it as the shard host, which alone may
     * mount a map ({@code docs/DESIGN-M3.md} {@code API-M3-006}).
     *
     * @param args  the command line
     * @param shard whether the shard host launches it
     */
    static void main(String[] args, boolean shard) {
        if (CONFIG_FILE.refusal() != null) {
            LOGGER.error("Refusing to start: {}", CONFIG_FILE.refusal());
            System.exit(1);
            return;
        }
        // Before anything is read from it: a key refused in step (1) names its value, this line where it came from
        LOGGER.info("Effective config file: {}", CONFIG_FILE.describe());
        if (Arrays.asList(args).contains(MIGRATE_ONLY)) {
            System.exit(migrateOnly(args, System.getenv()));
            return;
        }
        try {
            start(shard);
        } catch (IllegalArgumentException e) {
            // Every refusal of start is an IllegalArgumentException, NumberFormatException of ServerConfig
            // included, and ends with exit code 1. One without a cause is a setting written wrong: one ERROR
            // line, its stack at DEBUG. One with a cause, a database that cannot be reached or a port that
            // cannot be bound, keeps its stack.
            if (e.getCause() == null) {
                LOGGER.error("Refusing to start: {}", e.getMessage());
                LOGGER.debug("Refusing to start", e);
            } else {
                LOGGER.error("Refusing to start: {}", e.getMessage(), e);
            }
            System.exit(1);
        }
    }

    private static void start(boolean shard) {
        // (1) Process level configuration. The engine reads its own synaxis.* properties while the
        // process is being created, which is why this comes before init.
        final int port = port();
        final String shardId = shardId();
        final long ledgerMaxBytesPerDay = ledgerMaxBytesPerDay();
        final Set<String> selectedPlugins = selectedPlugins();
        final Auth auth = auth();
        final String storeMode = storeMode();
        final boolean whitelist = whitelist();
        // The plugin host reads it again when it is built; checked here, a misspelt phase refuses the start
        // before the database is touched
        HandshakeGate.phase();
        final CoreMode.Setting coreMode = CoreMode.setting();
        final InetSocketAddress metricsBind = HostMetrics.bindAddress();
        final ManagementSettings managementSettings = ManagementSettings.fromSystemProperties(System.getenv());
        // A map, which only the shard host mounts (docs/DESIGN-M3.md API-M3-006): its keys are all read here,
        // before anything touches the disk
        final @Nullable MapMount map = mapMount(shard, coreMode);
        // Before the engine reads synaxis.max-players, which the directory sets
        final @Nullable ShardDirectory directory = map == null ? null : shardDirectory(map.settings(), storeMode, shardId);
        // After the directory, which writes the capacity the socket cap of a shard follows
        if (shard) ShardMain.gateDefaults(System::getProperty).forEach(System::setProperty);
        applyViewRangeDefaults();

        // (2) The engine facade, not a bare ServerProcess: MinecraftServer#start is the only public
        // entry point doing bind, dispatcher and tick thread in the right order.
        final MinecraftServer server = MinecraftServer.init(auth);
        final ServerProcess process = server.process();

        // Boot self-check: the effective settings go in the log, so a launch argument that did not
        // land is visible here instead of at the first symptom. The configuration file is named in main.
        LOGGER.info("Effective {}", process.config());
        LOGGER.info("Effective {}", JvmFlags.describe());
        logAuth(auth);

        // (3) The one shallow dimension type every match is built on. It has to be registered here:
        // step (6) freezes the registries, and a dimension type registered after the port is open
        // would shift the dimension indices of the players already in game (§11.3).
        final RegistryKey<DimensionType> dimensionType = registerMatchDimensionType(process);
        // The map release is checked, and its dimension type and block handlers registered, while the
        // registries still take them; nothing is written yet
        final @Nullable ShardWorld shardWorld = map == null ? null
                : ShardWorld.prepare(process, map.settings(), map.synx(), map.overlay(), directory);

        // (4) Plugins, on this thread. A plugin builds its match here, before anything is bound.
        // An operator who asked for the core mode gets no plugin at all: that is the only way the
        // core mode is deterministic while a gameplay plugin sits on the classpath.
        final EventLedger ledger = new EventLedger(Path.of("logs"), shardId, ledgerMaxBytesPerDay);
        ledger.start();
        // A shard in the directory mode counts the first chunks of a transferred player into the
        // ledger, and hears about its ticket from the verifier the store builds
        final @Nullable ShardArrivals arrivals = directory == null ? null
                : new ShardArrivals(directory, ledger::record, System::currentTimeMillis);
        // The host's player section, which the target side of a transfer tells about a claimed ticket
        // (docs/DESIGN-PROD.md API-PROD-032)
        final PlayerStateSection playerState = new PlayerStateSection(process.registries());
        // The player store before the plugins, which register their sections in onEnable; a shard
        // whose database is not the one it expects refuses here, before anything is bound.
        final StoreRuntime store = openStore(storeMode, process, auth, shardId, whitelist, ledger,
                claimedBy(arrivals, playerState), System.getenv());
        // On the pool and the Redis link of the store; built only, nothing is read or started yet
        final @Nullable DirectoryMode directoryMode = directory == null || arrivals == null || map == null ? null
                : DirectoryMode.build(directory, arrivals, store, map.settings(), process);
        // The engine takes one participant: the players save first, then the steps registered on
        // this before the start, in their order (docs/DESIGN-PROD.md API-PROD-007). The RTS path
        // registers none, so the save phase is the player store's alone; a map shard registers the
        // final flush of its overlay store in step (5).
        final ShutdownSequence shutdown = new ShutdownSequence(process, store.store());
        process.setShutdownParticipant(shutdown);
        final PluginHost host = new PluginHost(process, Path.of("plugins"), ledger, store.store(), store.verifier(),
                store.permissions(), store.moderation(), store.cooldowns());
        // Before the plugins and the default play layer: the host node runs its own listeners in the order they were
        // added, so the saved state is applied at the first spawn before anything else of the host looks at it
        playerState.install(store.store(), host.hostNode());
        process.command().register(new RoleCommand(process, store.permissions(), ledger::record));
        new ModerationCommands(process, store.moderation(), store.permissions(), ledger::record).commands()
                .forEach(process.command()::register);
        final @Nullable ShardService shardService = directoryMode == null ? null : directoryMode.service();
        process.command().register(new TransferCommand(process, store.permissions(), shardService, ledger::record));
        // Before the plugins, so that the bare names of the built in commands are the host's
        final HostCommands commands = new HostCommands(process, store.permissions(), store.store(), ledger::record,
                new Profiler(Path.of("profiles"), shardId, Clock.systemUTC()));
        commands.register();
        final CrashReports crashReports = crashReports(process, host, shardId, Path.of("crash-reports"));
        installExceptionHandler(process, crashReports);
        final Watchdog watchdog = startWatchdog(process, crashReports);
        // Before the first tick, so every tick is timed; served before the port opens, so a shard
        // whose endpoint cannot be bound is refused rather than running unobserved
        final HostMetrics metrics = HostMetrics.register(process, new HostMetrics.Sources(ledger::droppedTotal,
                store.store(), store.session(), store.database(), store.redis()));
        serveMetrics(metrics, metricsBind);
        warnOfTheWildcard(HostMetrics.BIND_PROPERTY, metricsBind, "the metrics, which ask for no authentication");
        final ManagementMethods managementMethods = new ManagementMethods(process, commands, store.store(),
                store.moderation(), store.session(), shardService, shardId, ledger::record);
        final ManagementServer management = ManagementServer.serve(managementSettings, process, managementMethods);
        if (managementSettings.enabled()) {
            warnOfTheWildcard(ManagementSettings.BIND_PROPERTY, managementSettings.bind(),
                    "the management interface, whose token crosses the network in clear text (no TLS)");
        }
        if (coreMode != CoreMode.Setting.ON) host.enable(PluginHost.discover(), selectedPlugins);

        // (5) Which mode this process serves in. Gameplay needs both halves — an admission provider
        // to say where a player goes and a running match to put them in — and a launch with no
        // plugin to provide them serves the core's own world instead. A plugin that took part and
        // left one half or neither behind is a plugin that came up wrong: the port stays closed
        // rather than letting every player hit an internal error, or hiding the failure behind a
        // world nobody can play in.
        final int runningMatches = runningMatches(process);
        final CoreMode.Mode mode = CoreMode.decide(coreMode, host.attemptedPluginCount(),
                host.hasAdmissionProvider(), runningMatches);
        @Nullable InstanceContainer coreWorld = null;
        switch (mode) {
            case CORE -> {
                if (shardWorld == null) coreWorld = CoreMode.start(process, host, dimensionType, coreMode);
                else serveMap(process, host, shardWorld.start(shutdown, ledger::record,
                        directoryMode == null ? null : directoryMode.flags()), directoryMode, store.store(), ledger);
            }
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
        // The position rule of the mode; a refused launch has exited in its branch above
        playerState.useRule(positionRule(mode, shardWorld));
        // The default play layer of the mode, on the host node and before the port opens
        // (docs/DESIGN-PROD.md API-PROD-031)
        final Set<DefaultPlay.Feature> play = defaultPlay(mode, shardWorld != null);
        if (!play.isEmpty()) DefaultPlay.attach(process, host.hostNode(), play, playerState::isFresh, coreWorld);

        // The only place the plugins are disabled, console and SIGINT alike: the engine drains its
        // shutdown tasks after the players are saved and kicked, between two ticks, and before it
        // stops ticking (docs/DESIGN-PROD.md §11.4), so onDisable runs the same way on both paths.
        process.scheduler().buildShutdownTask(host::disableAll);
        // A stop still answering gets its answer and its ledger line out first (API-PROD-011)
        process.scheduler().buildShutdownTask(commands::awaitStopAnswers);
        // After the plugins, so that what they record in onDisable is on disk (API-PROD-014)
        process.scheduler().buildShutdownTask(ledger::close);
        // Before the store: both run on its pool and its Redis link
        if (directoryMode != null) {
            process.scheduler().buildShutdownTask(directoryMode.flags()::close);
            process.scheduler().buildShutdownTask(directoryMode.heartbeat()::close);
        }
        // The save is over by now: Redis, the session renewal and the pool can go
        process.scheduler().buildShutdownTask(store::close);
        process.scheduler().buildShutdownTask(metrics::close);
        process.scheduler().buildShutdownTask(management::close);
        // Last: a tick still in flight keeps being watched after this, see Watchdog#stop
        process.scheduler().buildShutdownTask(watchdog::stop);

        // (6) The shard session, then bind, freeze the registries, accept, start the dispatcher and the tick
        // thread. The session opens last before the bind: a launch refused anywhere above takes nothing over
        // from a running process of this shard id (docs/DESIGN-PROD.md API-PROD-004)
        openSession(process, store);
        try {
            server.start(BIND_ADDRESS, port);
        } catch (RuntimeException e) {
            if (!(e.getCause() instanceof IOException cause)) throw e;
            // Nothing ticks yet: the shutdown tasks above close what this start opened, the ledger
            // among them, and main refuses the start as it does for a metrics or management port
            process.scheduler().shutdown();
            throw new IllegalArgumentException(PORT_PROPERTY + "=" + port + " cannot be bound: " + cause.getMessage(),
                    cause);
        }
        // Not earlier: a stop begun before this would run ahead of the shutdown tasks above (§11.4)
        managementMethods.serverStarted();

        // (7) Printed after all three, so "the ready log is out" means the process is ticking. A map
        // shard records EVT:shard_started first, now that the port is bound. A shard in the directory
        // mode writes its first heartbeat before that and not earlier, so that no source shard sends a
        // player to a port that is not listening yet.
        if (directoryMode != null) directoryMode.heartbeat().start();
        if (shardWorld != null) ledger.record("shard_started", shardWorld.startedAttributes());
        LOGGER.info("synaxis server-app listening on {}:{}", BIND_ADDRESS, process.server().getPort());

        // A daemon: a stop runs on a thread of its own, and a console nobody types into must not
        // keep the JVM alive once that thread and the tick threads are gone.
        Thread.ofPlatform().name("server-app-console").daemon().start(() -> readConsole(process,
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))));
        // A daemon too: it only waits for the sequence a stop runs to end, then ends the JVM
        Thread.ofPlatform().name("server-app-exit").daemon().start(() -> exitOnceStopped(process));
    }

    /**
     * The keys of a mounted map ({@code docs/DESIGN-M3.md} {@code API-M3-006}): the host's, the
     * {@code :synx} ones and the overlay store's.
     *
     * @param settings the host keys
     * @param synx     the {@code :synx} keys
     * @param overlay  the overlay store keys
     */
    record MapMount(ShardSettings settings, SynxOptions synx, OverlayOptions overlay) {
    }

    /**
     * Reads the map keys in step (1). Without {@code synaxis.map.root} and {@code synaxis.map.build}
     * no map is mounted and none of the other map keys ({@link ShardSettings}, {@link SynxOptions},
     * {@link OverlayOptions}) is read. A map is only mounted by the shard host,
     * whose defaults (threads, view, chunk and entity caps) the server application does not write, and
     * serves no plugin.
     *
     * @param shard    whether the shard host launched the process
     * @param coreMode the core mode setting
     * @return the keys, null without a map
     * @throws IllegalArgumentException naming the key of a refused start
     */
    static @Nullable MapMount mapMount(boolean shard, CoreMode.Setting coreMode) {
        final ShardSettings settings = ShardSettings.fromSystemProperties();
        if (!settings.mountsMap()) return null;
        if (!shard) {
            throw new IllegalArgumentException(ShardSettings.MAP_ROOT + " and " + ShardSettings.MAP_BUILD
                    + " mount a map, which only the shard host does (bin/server-shard), not bin/server-app");
        }
        if (coreMode != CoreMode.Setting.ON) {
            throw new IllegalArgumentException("a map shard loads no plugin, " + CoreMode.PROPERTY + " must be on, not "
                    + coreMode.name().toLowerCase(Locale.ROOT));
        }
        return new MapMount(settings, SynxOptions.fromSystemProperties(), OverlayOptions.fromSystemProperties());
    }

    /**
     * Reads the shard directory of a map shard in step (1) ({@code docs/DESIGN-M3.md} {@code API-M3-010}).
     * The store alone decides the directory mode, the file is only checked against it: a shard storing
     * in PostgreSQL is in the directory mode and needs {@code <map_root>/shards.json}, one storing in
     * memory must not have it. In the directory mode the capacity of this shard's channel becomes
     * {@code synaxis.max-players}, which the engine reads in step (2). Reads the file only, writes nothing.
     *
     * @param settings the host keys, with a map mounted
     * @param store    {@code memory} or {@code postgres}
     * @param shardId  the shard id, which has to be a channel of the directory
     * @return the directory, null for a shard storing in memory
     * @throws IllegalArgumentException for a refused start
     */
    static @Nullable ShardDirectory shardDirectory(ShardSettings settings, String store, String shardId) {
        final Path file = Objects.requireNonNull(settings.mapRoot(), "no map is mounted")
                .resolve(ShardDirectory.FILE_NAME).toAbsolutePath();
        if (STORE_MEMORY.equals(store)) {
            if (Files.exists(file)) {
                throw new IllegalArgumentException("the shard directory " + file + " is only read by a shard storing "
                        + "in PostgreSQL (" + STORE_PROPERTY + "=" + STORE_POSTGRES + ")");
            }
            return null;
        }
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("a map shard storing in PostgreSQL needs its shard directory " + file
                    + ": no such file");
        }
        final ShardDirectory directory = ShardDirectory.load(file, shardId);
        applyDirectory(directory);
        return directory;
    }

    /**
     * What the shard directory decides for this process in step (1), once it is read
     * ({@code docs/DESIGN-M3.md} {@code API-M3-010}): the shard takes transfers, and the capacity of
     * its channel is {@code synaxis.max-players}, written when the key is not given. A given value is
     * compared as the number the engine reads it as, so {@code 0200} is 200; one that is not an integer
     * is left to {@code ServerConfig}, which refuses it in step (2) with its own message.
     *
     * @param directory the shard directory, whose channel of this shard counts
     * @throws IllegalArgumentException for a refused start
     */
    static void applyDirectory(ShardDirectory directory) {
        if ("false".equals(System.getProperty(ShardMain.ACCEPT_TRANSFERS))) {
            throw new IllegalArgumentException("a shard in the shard directory takes transfers, "
                    + ShardMain.ACCEPT_TRANSFERS + " must not be false");
        }
        final ShardDirectory.Channel channel = directory.ownChannel();
        final String maxPlayers = System.getProperty(MAX_PLAYERS_PROPERTY);
        if (maxPlayers == null) {
            System.setProperty(MAX_PLAYERS_PROPERTY, Integer.toString(channel.capacity()));
            return;
        }
        final int given;
        try {
            given = Integer.parseInt(maxPlayers);
        } catch (NumberFormatException _) {
            return;
        }
        if (given != channel.capacity()) {
            throw new IllegalArgumentException(MAX_PLAYERS_PROPERTY + "=" + maxPlayers + " disagrees with the capacity "
                    + channel.capacity() + " of " + channel.shardId() + " in the shard directory");
        }
    }

    /**
     * What a shard in the directory mode runs besides the map world ({@code docs/DESIGN-M3.md}
     * {@code API-M3-010}, {@code API-M3-013}).
     *
     * @param directory the shard directory
     * @param arrivals  the arrivals of transferred players
     * @param heartbeat the heartbeat, which also reads the other shards'
     * @param service   what sends a player to another shard, for the gates, {@code transfer} and {@code synaxis:transfer}
     * @param flags     the world state flags of the region
     */
    record DirectoryMode(ShardDirectory directory, ShardArrivals arrivals, ShardHeartbeat heartbeat,
                         ShardService service, WorldFlagsImpl flags) {

        /**
         * Builds the rest in step (4), once the store is open; reads nothing and starts nothing. A
         * shard in the directory mode stores in PostgreSQL and takes transfers, so the store has a
         * pool and a source side of a transfer.
         */
        static DirectoryMode build(ShardDirectory directory, ShardArrivals arrivals, StoreRuntime store,
                                   ShardSettings settings, ServerProcess process) {
            final Database database = Objects.requireNonNull(store.database(), "the directory mode stores in PostgreSQL");
            final TransferTickets issuer = Objects.requireNonNull(store.issuer(), "the directory mode takes transfers");
            final String buildId = Objects.requireNonNull(settings.mapBuild(), "no map is mounted");
            final ShardHeartbeat heartbeat = new ShardHeartbeat(database.pool(), directory, buildId, process);
            final ShardDirectory.Region region = directory.ownRegion();
            return new DirectoryMode(directory, arrivals, heartbeat, new ShardServiceImpl(directory, heartbeat, issuer),
                    new WorldFlagsImpl(database.pool(), store.redis(), directory.mapId(), region.regionId(),
                            region.bounds()));
        }
    }

    /**
     * What {@link CoreMode#start} takes from the host for the core world, for the map instance instead: every
     * connection goes into it, spawning on the spawn point of the map. A shard in the directory mode also gets its
     * gates, the arrivals of transferred players and the protection and synchronisation of its world state flags there.
     *
     * @param process       the engine process
     * @param host          the plugin host, whose admission slot the map takes
     * @param world         the started map world
     * @param directoryMode what the directory mode runs, null outside it
     * @param store         the player store, which tells the gates who is online here
     * @param ledger        the event ledger, where the gates write their refusals
     */
    private static void serveMap(ServerProcess process, PluginHost host, ShardWorld.Started world,
                                 @Nullable DirectoryMode directoryMode, PlayerStore store, EventLedger ledger) {
        process.eventHandler().addListener(AsyncPlayerConfigurationEvent.class,
                event -> event.getPlayer().setRespawnPoint(world.spawn()));
        host.registerHostAdmission(_ -> new Admission.Accept(world.instance()));
        if (directoryMode != null) {
            new ShardGates(directoryMode.directory(), directoryMode.service(), ledger::record, store::isOnline).attach(world.instance());
            directoryMode.arrivals().attach(world.instance());
            directoryMode.flags().start(world.instance());
        }
        LOGGER.info("Map mode ({}=on): the host serves the map instance, no plugin is loaded, spawn={}",
                CoreMode.PROPERTY, world.spawn());
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
        // Written on the dying thread, so the log pattern names it
        Thread.setDefaultUncaughtExceptionHandler((_, e) -> handler.handleException(e));
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
        watchdog.start(stuckMillis -> {
            // First, so that the exit of a stopped process leaves this one to stopAfterDeath and its 70
            JUDGED_DEAD.set(true);
            crashReports.write("tick watchdog: the tick has been stuck for " + stuckMillis
                    + " ms, the process is judged dead", null);
            stopAfterDeath(process);
        });
        return watchdog;
    }

    /**
     * Serves the metrics endpoint ({@code docs/DESIGN-PROD.md} {@code API-PROD-015}).
     *
     * @param metrics the metrics of the process
     * @param bind    where to serve them
     * @throws IllegalArgumentException if the address cannot be bound, which refuses the start
     */
    static void serveMetrics(HostMetrics metrics, InetSocketAddress bind) {
        try {
            metrics.serve(bind);
        } catch (IOException e) {
            metrics.close();
            throw new IllegalArgumentException(HostMetrics.BIND_PROPERTY + "=" + BindAddress.format(bind)
                    + " cannot be bound: " + e.getMessage(), e);
        }
    }

    /**
     * One WARN of the boot self-check for an operations port bound to the wildcard address
     * ({@code docs/DESIGN-PROD.md} {@code API-PROD-015}, {@code API-PROD-022}): it then listens on
     * every interface of the host. A specific internal address is the deployment both are meant for
     * and gets no line.
     *
     * @param key    the bind key
     * @param bind   what it is set to
     * @param served what every interface then serves
     */
    private static void warnOfTheWildcard(String key, InetSocketAddress bind, String served) {
        if (bind.getAddress() != null && bind.getAddress().isAnyLocalAddress())
            LOGGER.warn("{}={} is the wildcard address: every interface of this host serves {}", key,
                    BindAddress.format(bind), served);
    }

    /**
     * What a process judged dead still does before the watchdog halts it
     * ({@code docs/DESIGN-PROD.md} §11.4, §11.5 {@code dying → exited}): the shutdown sequence
     * without the stuck tick, saving what can be read on this thread, then exit code 70. Whatever
     * does not finish within {@code synaxis.watchdog.halt-after-ms} is cut short by the halt.
     */
    private static void stopAfterDeath(ServerProcess process) {
        // A shutdown that was already running picks the death up on its own thread
        if (!process.shutdownAfterWatchdogDeath() && !awaitStopped(process)) return;
        LOGGER.error("The process was judged dead and has stopped, exiting with code {}", DEATH_EXIT_CODE);
        System.exit(DEATH_EXIT_CODE);
    }

    /**
     * Ends the JVM with 0 once the shutdown sequence has stopped ({@code docs/DESIGN-PROD.md} §11.4
     * {@code plugins_disabled → stopped}): a non daemon thread a plugin left behind would otherwise keep a stopped
     * process alive, and its unit is only restarted once the process exits. A process judged dead is left to
     * {@link #stopAfterDeath}. On SIGTERM the exit waits for the shutdown hooks, and the code stays 143.
     */
    private static void exitOnceStopped(ServerProcess process) {
        if (awaitStopped(process) && !JUDGED_DEAD.get()) System.exit(0);
    }

    /**
     * Waits for the shutdown sequence of the process to reach {@code stopped}.
     *
     * @param process the engine process
     * @return false if the waiting thread was interrupted first
     */
    static boolean awaitStopped(ServerProcess process) {
        while (process.shutdownPhase() != ServerProcess.ShutdownPhase.STOPPED) {
            try {
                Thread.sleep(DEATH_POLL_MILLIS);
            } catch (InterruptedException _) {
                return false;
            }
        }
        return true;
    }

    /**
     * The player store of the process and what it holds open.
     *
     * @param store        the store
     * @param database     the pool of a shard, null in memory
     * @param housekeeping the cleanup jobs of a shard, null in memory
     * @param issuer       the source side of a transfer, null without transfer keys
     * @param verifier     the target side of a transfer, null without transfer keys
     * @param permissions  the roles of the online players, none in memory
     * @param moderation   the bans and the whitelist, none in memory
     * @param session      the shard session, null in memory
     * @param redis        the Redis link of the store, disabled in memory or without Redis
     * @param cooldowns    the transfer cooldowns of the process, one table for the plugins' transfers and both
     *                     sides of a ticket (docs/DESIGN-PROD.md API-PROD-021)
     */
    record StoreRuntime(PlayerStore store, @Nullable Database database, @Nullable StoreHousekeeping housekeeping,
                        @Nullable TransferTickets issuer, @Nullable TicketVerifier verifier, HostPermissions permissions,
                        HostModeration moderation, @Nullable ShardSession session, RedisLink redis,
                        TransferCooldowns cooldowns)
            implements AutoCloseable {
        @Override
        public void close() {
            if (housekeeping != null) housekeeping.close();
            store.close();
            if (database != null) database.close();
        }
    }

    /**
     * {@link #openStore(String, ServerProcess, Auth, String, boolean, EventLedger, BiConsumer, Map)} outside the
     * directory mode, where nothing else hears about a claimed transfer ticket.
     */
    static StoreRuntime openStore(String mode, ServerProcess process, Auth auth, String shardId, boolean whitelist,
                                  EventLedger ledger, Map<String, String> environment) {
        return openStore(mode, process, auth, shardId, whitelist, ledger, (_, _) -> {}, environment);
    }

    /**
     * Opens the shard session ({@code docs/DESIGN-PROD.md} §11.2 {@code [*] → active}), which makes every other
     * session of this shard id expire. {@link #start} calls it right before the game port is bound, once every
     * shutdown task is registered, so that a launch refused at an operations port, by a plugin or by the mode
     * takes nothing over from a running process of the same shard id.
     *
     * @param process the engine process, whose shutdown tasks close what the start opened when this fails
     * @param store   the player store, which has no session in memory
     * @throws IllegalArgumentException if the database failed, which refuses the start
     */
    static void openSession(ServerProcess process, StoreRuntime store) {
        final ShardSession session = store.session();
        if (session == null) return;
        try {
            session.open();
        } catch (SQLException e) {
            process.scheduler().shutdown();
            throw new IllegalArgumentException("the player store database failed: " + e.getMessage(), e);
        }
    }

    /**
     * Opens the player store ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-005}): memory for a
     * process without a database, PostgreSQL for a shard. A shard checks its database before
     * anything else and refuses to start on a schema that is not this build's, or on a store that
     * holds other identities than this process lets in ({@code REQ-PROD-036}). The shard session is built but
     * not opened: {@link #openSession} opens it right before the port is bound.
     *
     * @param mode        {@code memory} or {@code postgres}
     * @param process     the engine process
     * @param auth        the auth of the process
     * @param shardId     the shard id
     * @param whitelist   whether {@code synaxis.app.whitelist} turns the whitelist on, read in step (1)
     * @param ledger      the event ledger
     * @param onClaimed   told about every transfer ticket the target side accepts
     *                    ({@code docs/DESIGN-M3.md} {@code API-M3-010}, the arrivals of a shard in the
     *                    directory mode; {@code API-PROD-032}, the player section)
     * @param environment where the database settings come from
     * @return the store
     * @throws IllegalArgumentException if the shard must not start
     */
    static StoreRuntime openStore(String mode, ServerProcess process, Auth auth, String shardId, boolean whitelist,
                                  EventLedger ledger, BiConsumer<Player, TransferTicket> onClaimed,
                                  Map<String, String> environment) {
        if (STORE_MEMORY.equals(mode)) {
            // A ticket's nonce and lease live in the database, a process without one takes no transfer
            if (process.config().acceptTransfers())
                throw new IllegalArgumentException("synaxis.accept-transfers=true needs the postgres store "
                        + "(-Dsynaxis.app.store=postgres): transfer tickets are checked against the database");
            LOGGER.info("Effective store: mode=memory, player data lives as long as the process");
            final RedisLink noRedis = RedisLink.disabled(shardId);
            final HostModeration moderation = new HostModeration(process, null, noRedis,
                    HostModeration.REFRESH, whitelist, ledger::record);
            return new StoreRuntime(PlayerStore.inMemory(process, ledger::record), null, null, null, null,
                    HostPermissions.withoutDatabase(process), moderation, null, noRedis,
                    TransferCooldowns.fromSystemProperties());
        }
        final StoreSettings settings = StoreSettings.fromEnvironment(environment);
        final TransferKeys keys = TransferKeys.fromEnvironment(environment);
        final TransferCooldowns cooldowns = TransferCooldowns.fromSystemProperties();
        if (keys == null && process.config().acceptTransfers())
            throw new IllegalArgumentException("synaxis.accept-transfers=true needs " + TransferKeys.VARIABLE
                    + " to check the tickets transfers come in with (see .env.example)");
        final AuthMode effective = AuthMode.effective(auth);
        final Database database;
        try {
            database = new Database(settings);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the player store database cannot be reached: " + e.getMessage(), e);
        }
        try {
            Migrations.validate(database.pool());
            Migrations.requireAuthMode(database.pool(), effective);
            final RedisLink redis = settings.redisUrl() == null ? RedisLink.disabled(shardId)
                    : RedisLink.connect(settings.redisUrl(), shardId);
            if (settings.redisUrl() == null)
                LOGGER.warn("SYNAXIS_REDIS_URL is not set: a login of an account online on another shard is refused");
            final PlayerStore store = new PlayerStore(process, new PostgresBackend(database.pool()), redis, shardId,
                    auth instanceof Auth.Online, PlayerStore.Options.DEFAULT, ledger::record);
            final ShardSession session = new ShardSession(database::dedicated, shardId, ShardSession.Timing.DEFAULT,
                    System::nanoTime, store::sessionChanged);
            store.useSession(session);
            final StoreHousekeeping housekeeping = new StoreHousekeeping(database.pool());
            housekeeping.start();
            final HostPermissions permissions = new HostPermissions(process, new RoleStore(database.pool()), redis,
                    HostPermissions.REFRESH);
            final HostModeration moderation = new HostModeration(process, new ModerationStore(database.pool()), redis,
                    HostModeration.REFRESH, whitelist, ledger::record);
            moderation.start();
            if (whitelist) {
                final int accounts = moderation.whitelistSize();
                LOGGER.info("Effective whitelist: on accounts={}", accounts);
                // Nobody is online to be found by name, the console has to add an account by its UUID
                if (accounts == 0)
                    LOGGER.warn("{}=on and the whitelist is empty: every login is refused until the console runs "
                            + "whitelist add <uuid>", WHITELIST_PROPERTY);
            } else {
                LOGGER.info("Effective whitelist: off");
            }
            LOGGER.info("Effective store: mode=postgres auth_mode={} {}", effective.value(), settings);
            if (keys == null)
                return new StoreRuntime(store, database, housekeeping, null, null, permissions, moderation, session, redis,
                        cooldowns);
            LOGGER.info("Effective transfers: {} accept={}", keys, process.config().acceptTransfers());
            return new StoreRuntime(store, database, housekeeping,
                    new TicketIssuer(store, keys, cooldowns, shardId, ledger::record),
                    new TicketVerifier(database.pool(), session::sessionId, shardId, keys, cooldowns, ledger::record,
                            onClaimed),
                    permissions, moderation, session, redis, cooldowns);
        } catch (SQLException e) {
            database.close();
            throw new IllegalArgumentException("the player store database failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            database.close();
            throw e;
        }
    }

    /**
     * {@code server-app --migrate-only --auth-mode=online|offline}: the release job's migration of
     * the player store ({@code docs/DESIGN-PROD.md} {@code API-PROD-004}, §25). Migrates, records
     * whose identities the store holds, and exits; binds nothing.
     *
     * @param args        the command line
     * @param environment where the database settings come from
     * @return the exit code, 0 once migrated
     */
    static int migrateOnly(String[] args, Map<String, String> environment) {
        final String mode = Arrays.stream(args).filter(arg -> arg.startsWith(AUTH_MODE_ARGUMENT))
                .map(arg -> arg.substring(AUTH_MODE_ARGUMENT.length())).findFirst().orElse(null);
        try {
            if (mode == null)
                throw new IllegalArgumentException(MIGRATE_ONLY + " needs " + AUTH_MODE_ARGUMENT + "online|offline");
            final AuthMode authMode = AuthMode.parse(mode);
            try (Database database = Database.forMigration(StoreSettings.fromEnvironment(environment))) {
                Migrations.migrate(database.pool(), authMode);
            }
            LOGGER.info("Migrated the player store: auth_mode={}", authMode.value());
            return 0;
        } catch (IllegalArgumentException | SQLException e) {
            LOGGER.error("Refusing to migrate: {}", e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            LOGGER.error("Refusing to migrate: {}", e.getMessage(), e);
            return 1;
        }
    }

    /**
     * @return whether {@code synaxis.app.whitelist} turns the whitelist on ({@code docs/DESIGN-PROD.md} §23),
     *         off when it is not set
     * @throws IllegalArgumentException for a value other than on or off
     */
    static boolean whitelist() {
        final String value = System.getProperty(WHITELIST_PROPERTY, "off").strip();
        if (!"on".equals(value) && !"off".equals(value))
            throw new IllegalArgumentException(WHITELIST_PROPERTY + "=" + value + " is not on or off");
        return "on".equals(value);
    }

    /**
     * @return {@value #STORE_MEMORY} unless {@code synaxis.app.store} says {@value #STORE_POSTGRES}
     * @throws IllegalArgumentException for any other value
     */
    static String storeMode() {
        final String mode = System.getProperty(STORE_PROPERTY, STORE_MEMORY).strip();
        if (!STORE_MEMORY.equals(mode) && !STORE_POSTGRES.equals(mode))
            throw new IllegalArgumentException(STORE_PROPERTY + "=" + mode + " is not a store: memory or postgres");
        return mode;
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
     * Reports what a gameplay launch is missing and exits without binding. A plugin whose
     * {@code onEnable} threw is named first, with what it threw. Both halves are reported, because
     * which one is missing says what went wrong with the plugin.
     *
     * @param host    the plugin host
     * @param ledger  the event ledger, drained before the exit so the plugin events stay
     * @param matches how many matches are running, the count the mode was decided on
     * @param port    the port that is not going to be bound
     */
    private static void refuseToServe(PluginHost host, EventLedger ledger, int matches, int port) {
        // What a plugin that failed to enable left behind is what is missing below; its stack is further up
        host.failedToEnable().forEach((id, failure) ->
                LOGGER.error("Plugin {} failed to enable: {} (stack above)", id, failure));
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
     * Which features of the default play layer a mode gets ({@code docs/DESIGN-PROD.md} {@code API-PROD-031},
     * {@code ADR-PROD-017}): every one in the core world, the chat alone under a gameplay plugin, and none on a map
     * shard, whose play rules are left to the gameplay design, nor in a refused launch.
     *
     * @param mode       the mode of step (5)
     * @param mapMounted whether the process serves a map
     * @return the features to attach, empty for no play layer at all
     */
    static Set<DefaultPlay.Feature> defaultPlay(CoreMode.Mode mode, boolean mapMounted) {
        return switch (mode) {
            case CORE -> mapMounted ? Set.of() : EnumSet.allOf(DefaultPlay.Feature.class);
            case GAMEPLAY -> EnumSet.of(DefaultPlay.Feature.CHAT);
            case REFUSED -> Set.of();
        };
    }

    /**
     * The position rule of {@code synaxis:player} in a mode ({@code docs/DESIGN-PROD.md} {@code API-PROD-032}): the
     * core world's in the core world, and on a map shard, always in the core mode, the release and the bounds
     * {@code ShardWorld} read in step (3); under a gameplay plugin the plugin's spawn decides.
     *
     * @param mode       the mode of step (5)
     * @param shardWorld the map the process serves, null for none
     * @return the rule to set
     */
    static PositionRule positionRule(CoreMode.Mode mode, @Nullable ShardWorld shardWorld) {
        return mode != CoreMode.Mode.CORE ? new PositionRule.Rts()
                : shardWorld == null ? new PositionRule.Core()
                : new PositionRule.MapShard(shardWorld.mapId(), shardWorld.buildId(), shardWorld.bounds());
    }

    /**
     * What the target side of a transfer tells about a claimed ticket: the arrivals of the directory mode, then the
     * player section, which leaves the player at its arrival point ({@code docs/DESIGN-PROD.md} {@code API-PROD-032}).
     *
     * @param arrivals    the arrivals of a shard in the directory mode, null outside it
     * @param playerState the player section
     * @return the callback {@code openStore} takes
     */
    static BiConsumer<Player, TransferTicket> claimedBy(@Nullable ShardArrivals arrivals, PlayerStateSection playerState) {
        return (player, ticket) -> {
            if (arrivals != null) arrivals.claimed(player, ticket);
            playerState.claimed(player);
        };
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
     * @throws IllegalArgumentException if the property is set to something that is not a number from 0 to 65535
     */
    static int port() {
        final String property = System.getProperty(PORT_PROPERTY);
        if (property == null) return DEFAULT_PORT;
        final int port;
        try {
            port = Integer.parseInt(property.strip());
        } catch (NumberFormatException _) {
            // Integer.getInteger would fall back to the default here, and the process would come up
            // on a port nobody asked for (docs/TEST.md §3 reads this one back as a precondition).
            throw new IllegalArgumentException(PORT_PROPERTY + "=" + property + " is not a port number");
        }
        // In step (1), naming the key: the bind would refuse it too, without the key and after the plugins
        if (port < 0 || port > 65535)
            throw new IllegalArgumentException(PORT_PROPERTY + "=" + property + " is not a port number: 0 to 65535");
        return port;
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

    /**
     * Runs every line of the console until it ends. A line whose command throws anything, from its
     * condition, an argument callback or a listener the engine does not guard, is one line in the
     * log with its stack, and the next line runs.
     *
     * @param process the engine process
     * @param console the console input, closed once it ends
     */
    static void readConsole(ServerProcess process, BufferedReader console) {
        try (BufferedReader reader = console) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    runConsoleCommand(process, line);
                } catch (Throwable e) {
                    LOGGER.error("Console command failed: {}", line.strip(), e);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Console reader stopped", e);
        }
    }

    /**
     * Runs one console line as a command ({@code docs/DESIGN-PROD.md} {@code API-PROD-011}), on
     * the calling thread and with the console sender, which may run every command. A leading
     * {@code /} is accepted. A line that is no command gets one line in the log, never a stack, and so
     * does a command its condition refuses or whose executor failed.
     *
     * @param process the engine process
     * @param line    what the operator typed
     * @return the result
     */
    static CommandResult runConsoleCommand(ServerProcess process, String line) {
        final String stripped = line.strip();
        final String input = stripped.startsWith(CommandManager.COMMAND_PREFIX)
                ? stripped.substring(CommandManager.COMMAND_PREFIX.length()) : stripped;
        final CommandManager commands = process.command();
        final CommandResult result = commands.execute(commands.getConsoleSender(), input);
        switch (result.getType()) {
            case UNKNOWN -> LOGGER.info("Unknown command: {}", input);
            case INVALID_SYNTAX -> LOGGER.info("Incomplete or invalid command: {}", input);
            // The console fires no PlayerCommandEvent: a cancelled command was refused by its condition, or
            // its executor threw and the engine logged that
            case CANCELLED -> LOGGER.info("Command refused by its condition or failed: {}", input);
            default -> {
                // the command answered for itself
            }
        }
        return result;
    }
}
