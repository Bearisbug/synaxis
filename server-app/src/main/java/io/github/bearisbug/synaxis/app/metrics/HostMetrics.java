package io.github.bearisbug.synaxis.app.metrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.TickSchedulerThread;
import io.github.bearisbug.synaxis.app.BindAddress;
import io.github.bearisbug.synaxis.app.store.Database;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.app.store.ShardSession;
import io.github.bearisbug.synaxis.app.store.StoreMetrics;
import io.github.bearisbug.synaxis.event.server.ServerTickMonitorEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.ConnectionGate;
import io.github.bearisbug.synaxis.network.ConnectionManager;
import io.github.bearisbug.synaxis.network.TrafficCounters;
import io.github.bearisbug.synaxis.network.player.SessionClient;
import io.github.bearisbug.synaxis.plugin.SaveResult;
import io.github.bearisbug.synaxis.thread.TickThread;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * The metrics endpoint of a process ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-018},
 * {@code API-PROD-015}): Micrometer with the Prometheus registry, served as Prometheus text on
 * {@code GET /metrics} by the JDK HTTP server, without authentication, on the address of
 * {@value #BIND_PROPERTY} ({@value #DEFAULT_BIND} when unset), which is meant to be an internal one.
 * <p>
 * The names are those of {@code API-PROD-015}, listed in {@link #NAMES}; {@code jvm_*} and
 * {@code process_*} come from Micrometer's own binders. Nothing here runs per packet: the engine
 * counts into {@link TrafficCounters} and the endpoint reads the sums when it is scraped.
 */
public final class HostMetrics implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(HostMetrics.class);

    public static final String BIND_PROPERTY = "synaxis.app.metrics.bind";
    public static final String DEFAULT_BIND = "127.0.0.1:9464";
    public static final String PATH = "/metrics";
    /**
     * How long one scrape may take, from the first byte of its request to the last of its answer:
     * a scrape Prometheus has not got by then it gave up on already (its default timeout is 10 s).
     */
    static final Duration EXCHANGE_LIMIT = Duration.ofSeconds(10);
    /**
     * The connections the endpoint holds open at most, idle ones included; the JDK closes one more as
     * it accepts it. The JDK reads the cap from {@value #MAX_CONNECTIONS_PROPERTY} once, as the first
     * {@code HttpServer} of the JVM is made, and this endpoint is the only one a host process makes.
     */
    static final int MAX_CONNECTIONS = 64;
    static final String MAX_CONNECTIONS_PROPERTY = "jdk.httpserver.maxConnections";

    /**
     * The metric families of {@code API-PROD-015}, as they appear in the Prometheus text: a counter
     * with its {@code _total} suffix, a summary under its base name ({@code _count}, {@code _sum}
     * and the {@code _max} gauge Micrometer adds belong to it).
     */
    public static final List<String> NAMES = List.of(
            "synaxis_tick_duration_seconds",
            "synaxis_tick_acquire_seconds",
            "synaxis_players_online",
            "synaxis_connections",
            "synaxis_gate_rejections_total",
            "synaxis_packets_total",
            "synaxis_bytes_total",
            "synaxis_entities",
            "synaxis_chunks_loaded",
            "synaxis_auth_hasjoined_total",
            "synaxis_auth_hasjoined_retries_total",
            "synaxis_auth_hasjoined_seconds",
            "synaxis_db_statement_seconds",
            "synaxis_db_pool_active",
            "synaxis_db_pool_pending",
            "synaxis_store_save_total",
            "synaxis_shard_session_state",
            "synaxis_redis_up",
            "synaxis_ledger_dropped_total",
            "synaxis_outbound_backlog_kicks_total",
            "synaxis_watchdog_warnings_total",
            "synaxis_cipher_cpu_seconds_total");

    /** How often the world figures are counted, in ticks: once a second at 20 TPS. */
    private static final int WORLD_SAMPLE_TICKS = 20;
    private static final double[] PERCENTILES = {0.5, 0.95, 0.99};

    /**
     * Where the host's own figures come from.
     *
     * @param ledgerDropped the lines the event ledger dropped since the process started
     * @param store         the player store
     * @param session       the shard session, null without a database
     * @param database      the pool, null without a database
     * @param redis         the Redis link, {@link RedisLink#disabled} without Redis
     */
    public record Sources(LongSupplier ledgerDropped, PlayerStore store, @Nullable ShardSession session,
                          @Nullable Database database, RedisLink redis) {
    }

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final JvmGcMetrics gc = new JvmGcMetrics();
    private final AtomicLong entities = new AtomicLong();
    private final AtomicLong chunks = new AtomicLong();
    private @Nullable HttpServer http;
    /**
     * Held here because a {@link FunctionCounter} keeps only a weak reference to what it reads: the
     * host hands in a lambda nothing else holds, which the first GC would take.
     */
    private @Nullable LongSupplier ledgerDropped;

    private HostMetrics() {
    }

    /**
     * Registers every metric of the process and hooks the tick threads, the session client and the
     * store up to it. Called before the first tick, so that every tick is timed.
     *
     * @param process the engine process
     * @param sources the host's own figures
     * @return the metrics, not served yet
     */
    public static HostMetrics register(ServerProcess process, Sources sources) {
        final HostMetrics metrics = new HostMetrics();
        metrics.registerTicks(process);
        metrics.registerConnections(process.connection());
        metrics.registerWorld(process);
        metrics.registerAuth(process.connection().sessionClient());
        metrics.registerStore(sources);
        final PrometheusMeterRegistry registry = metrics.registry;
        FunctionCounter.builder("synaxis.watchdog.warnings", process.watchdog(), watchdog -> watchdog.warnings())
                .description("Early warnings of the tick watchdog").register(registry);
        FunctionCounter.builder("synaxis.outbound.backlog.kicks", process.connection().traffic(),
                        TrafficCounters::backlogKicks)
                .description("Connections kicked for an outbound backlog").register(registry);
        new JvmMemoryMetrics().bindTo(registry);
        metrics.gc.bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ClassLoaderMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);
        new FileDescriptorMetrics().bindTo(registry);
        return metrics;
    }

    private void registerTicks(ServerProcess process) {
        for (TickThread thread : process.dispatcher().threads()) {
            final Timer timer = tickTimer(thread.getName());
            final Timer acquire = acquireTimer(thread.getName());
            thread.observeTicks((nanos, acquireNanos) -> {
                timer.record(nanos, TimeUnit.NANOSECONDS);
                acquire.record(acquireNanos, TimeUnit.NANOSECONDS);
            });
        }
        // The whole tick, run by the scheduler thread; its duration and acquisition wait are the ones
        // the monitor event carries
        final Timer scheduler = tickTimer(TickSchedulerThread.THREAD_NAME_TICK_SCHEDULER);
        final Timer schedulerAcquire = acquireTimer(TickSchedulerThread.THREAD_NAME_TICK_SCHEDULER);
        process.eventHandler().addListener(ServerTickMonitorEvent.class, event -> {
            scheduler.record((long) (event.getTickMonitor().getTickTime() * 1e6), TimeUnit.NANOSECONDS);
            schedulerAcquire.record((long) (event.getTickMonitor().getAcquisitionTime() * 1e6), TimeUnit.NANOSECONDS);
        });
    }

    private Timer tickTimer(String thread) {
        return Timer.builder("synaxis.tick.duration").tag("thread", thread).publishPercentiles(PERCENTILES)
                .description("Duration of one tick of a tick thread or of the tick scheduler").register(registry);
    }

    private Timer acquireTimer(String thread) {
        return Timer.builder("synaxis.tick.acquire").tag("thread", thread).publishPercentiles(PERCENTILES)
                .description("Time one tick of a tick thread, or the whole tick, waited to acquire").register(registry);
    }

    private void registerConnections(ConnectionManager connections) {
        Gauge.builder("synaxis.players.online", connections::getOnlinePlayerCount)
                .description("Players in the play state").register(registry);
        Gauge.builder("synaxis.connections", () -> connections.getConfigPlayers().size())
                .tag("state", "configuration").register(registry);
        Gauge.builder("synaxis.connections", connections::getOnlinePlayerCount)
                .tag("state", "play").register(registry);
        // Every socket the gate counts that is neither: handshake, status and login
        Gauge.builder("synaxis.connections", () -> Math.max(0, connections.gate().connections()
                        - connections.getConfigPlayers().size() - connections.getOnlinePlayerCount()))
                .tag("state", "login").register(registry);
        final ConnectionGate gate = connections.gate();
        for (ConnectionGate.Reason reason : ConnectionGate.Reason.values()) {
            FunctionCounter.builder("synaxis.gate.rejections", gate, g -> g.rejected(reason))
                    .tag("reason", reason.name().toLowerCase(Locale.ROOT))
                    .description("Connections the connection gate turned away").register(registry);
        }
        final TrafficCounters traffic = connections.traffic();
        traffic("synaxis.packets", traffic, TrafficCounters::packetsIn, TrafficCounters::packetsOut);
        traffic("synaxis.bytes", traffic, TrafficCounters::bytesIn, TrafficCounters::bytesOut);
        FunctionCounter.builder("synaxis.cipher.cpu.seconds", traffic, t -> t.decryptNanos() / 1e9)
                .tag("direction", "in").register(registry);
        FunctionCounter.builder("synaxis.cipher.cpu.seconds", traffic, t -> t.encryptNanos() / 1e9)
                .tag("direction", "out").register(registry);
    }

    private void traffic(String name, TrafficCounters traffic, ToLongFunction<TrafficCounters> in,
                         ToLongFunction<TrafficCounters> out) {
        FunctionCounter.builder(name, traffic, in::applyAsLong).tag("direction", "in").register(registry);
        FunctionCounter.builder(name, traffic, out::applyAsLong).tag("direction", "out").register(registry);
    }

    private void registerWorld(ServerProcess process) {
        Gauge.builder("synaxis.entities", entities, AtomicLong::get).description("Entities in every instance")
                .register(registry);
        Gauge.builder("synaxis.chunks.loaded", chunks, AtomicLong::get).description("Chunks loaded in every instance")
                .register(registry);
        // Counted between two ticks by the scheduler, which is where the instances are read safely
        process.scheduler().scheduleTask(() -> {
            long entityCount = 0;
            long chunkCount = 0;
            for (Instance instance : process.instance().getInstances()) {
                entityCount += instance.getEntities().size();
                chunkCount += instance.getChunks().size();
            }
            entities.set(entityCount);
            chunks.set(chunkCount);
            return TaskSchedule.tick(WORLD_SAMPLE_TICKS);
        }, TaskSchedule.nextTick());
    }

    private void registerAuth(@Nullable SessionClient client) {
        final String[] results = {"verified", "unverified", "unavailable"};
        for (int index = 0; index < results.length; index++) {
            final int result = index;
            FunctionCounter.builder("synaxis.auth.hasjoined", client,
                            c -> c == null ? 0 : c.outcomes()[result])
                    .tag("result", results[index]).description("Logins by the final answer of hasJoined")
                    .register(registry);
        }
        FunctionCounter.builder("synaxis.auth.hasjoined.retries", client, c -> c == null ? 0 : c.retries())
                .description("Retries of an unavailable hasJoined").register(registry);
        final Timer query = Timer.builder("synaxis.auth.hasjoined").publishPercentiles(PERCENTILES)
                .description("Duration of one hasJoined query").register(registry);
        if (client != null) client.observeQueries(nanos -> query.record(nanos, TimeUnit.NANOSECONDS));
    }

    private void registerStore(Sources sources) {
        final Map<StoreMetrics.Statement, Timer> statements = new EnumMap<>(StoreMetrics.Statement.class);
        for (StoreMetrics.Statement statement : StoreMetrics.Statement.values()) {
            statements.put(statement, Timer.builder("synaxis.db.statement")
                    .tag("op", statement.name().toLowerCase(Locale.ROOT)).publishPercentiles(PERCENTILES)
                    .description("Duration of one statement of the player store").register(registry));
        }
        final Map<SaveResult, Counter> saves = new EnumMap<>(SaveResult.class);
        for (SaveResult result : SaveResult.values()) {
            saves.put(result, Counter.builder("synaxis.store.save").tag("result", result.name().toLowerCase(Locale.ROOT))
                    .description("Saves of a character by their outcome").register(registry));
        }
        final StoreMetrics store = new StoreMetrics() {
            @Override
            public void statement(Statement statement, long nanos) {
                statements.get(statement).record(nanos, TimeUnit.NANOSECONDS);
            }

            @Override
            public void saved(SaveResult result) {
                saves.get(result).increment();
            }
        };
        sources.store().useMetrics(store);
        final ShardSession session = sources.session();
        if (session != null) session.useMetrics(store);
        for (ShardSession.State state : ShardSession.State.values()) {
            Gauge.builder("synaxis.shard.session.state", () -> session != null && session.state() == state ? 1 : 0)
                    .tag("state", state.name().toLowerCase(Locale.ROOT)).register(registry);
        }
        final Database database = sources.database();
        Gauge.builder("synaxis.db.pool.active", () -> database == null ? 0 : database.activeConnections())
                .register(registry);
        Gauge.builder("synaxis.db.pool.pending", () -> database == null ? 0 : database.pendingThreads())
                .register(registry);
        final RedisLink redis = sources.redis();
        Gauge.builder("synaxis.redis.up", () -> redis.isUp() ? 1 : 0).register(registry);
        ledgerDropped = sources.ledgerDropped();
        FunctionCounter.builder("synaxis.ledger.dropped", ledgerDropped, LongSupplier::getAsLong)
                .description("Lines the event ledger dropped").register(registry);
    }

    /**
     * Returns the Prometheus text of every metric right now.
     */
    public String scrape() {
        return registry.scrape();
    }

    /**
     * Starts serving {@value #PATH} on {@code address}.
     *
     * @param address where to listen
     * @return the address it listens on, its port chosen when {@code address} asked for 0
     * @throws IOException if the address cannot be bound
     */
    public synchronized InetSocketAddress serve(InetSocketAddress address) throws IOException {
        if (http != null) throw new IllegalStateException("The metrics endpoint is already served");
        // A value given on the command line is the operator's
        if (System.getProperty(MAX_CONNECTIONS_PROPERTY) == null)
            System.setProperty(MAX_CONNECTIONS_PROPERTY, Integer.toString(MAX_CONNECTIONS));
        final HttpServer server = HttpServer.create(address, 0);
        server.createContext(PATH, this::handle);
        server.setExecutor(HostMetrics::answer);
        startOnADaemon(server);
        this.http = server;
        final InetSocketAddress bound = server.getAddress();
        LOGGER.info("Metrics endpoint listening on http://{}{}", BindAddress.format(bound), PATH);
        return bound;
    }

    /**
     * Starts the server from a daemon thread. The JDK's dispatcher thread, {@code HTTP-Dispatcher},
     * is a daemon only when the thread starting the server is one; started from main it kept alive a
     * process whose start failed after this, with no tick thread running and the port answering.
     */
    private static void startOnADaemon(HttpServer server) throws IOException {
        final Thread starter = Thread.ofPlatform().name("Syn-Metrics-Start").daemon().start(server::start);
        try {
            starter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.stop(0);
            throw new IOException("interrupted while starting the metrics endpoint", e);
        }
    }

    /**
     * Runs one exchange of the HTTP server on a thread of its own, and cuts it short once it has
     * taken {@link #EXCHANGE_LIMIT}. A peer that stops halfway through its request, or stops reading
     * the answer, then holds its own thread for that long and no other scrape waits behind it; the
     * JDK server has no time limit of its own. Interrupting a thread blocked on a socket channel
     * closes the channel, the one way to end a read or a write that does not return.
     */
    private static void answer(Runnable exchange) {
        final Thread thread = Thread.ofVirtual().name("Syn-Metrics").start(exchange);
        Thread.ofVirtual().name("Syn-Metrics-Limit").start(() -> {
            try {
                if (!thread.join(EXCHANGE_LIMIT)) thread.interrupt();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod()) || !PATH.equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders("GET".equals(exchange.getRequestMethod()) ? 404 : 405, -1);
                return;
            }
            final byte[] body = scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    /**
     * Reads {@value #BIND_PROPERTY}.
     *
     * @return the address to serve on
     * @throws IllegalArgumentException if it is not {@code host:port}
     */
    public static InetSocketAddress bindAddress() {
        return bindAddress(System.getProperty(BIND_PROPERTY, DEFAULT_BIND));
    }

    static InetSocketAddress bindAddress(String value) {
        return BindAddress.parse(BIND_PROPERTY, value);
    }

    @Override
    public synchronized void close() {
        final HttpServer server = http;
        if (server != null) {
            server.stop(0);
            http = null;
        }
        gc.close();
        registry.close();
    }
}
