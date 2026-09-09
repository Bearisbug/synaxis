package dev.gameengine;

import dev.gameengine.adventure.ClickCallbackManager;
import dev.gameengine.adventure.audience.Audiences;
import dev.gameengine.adventure.bossbar.BossBarManager;
import dev.gameengine.command.CommandManager;
import dev.gameengine.entity.Entity;
import dev.gameengine.event.GlobalEventHandler;
import dev.gameengine.event.server.ServerTickMonitorEvent;
import dev.gameengine.exception.ExceptionManager;
import dev.gameengine.instance.Chunk;
import dev.gameengine.instance.Instance;
import dev.gameengine.instance.InstanceManager;
import dev.gameengine.instance.block.BlockManager;
import dev.gameengine.listener.manager.PacketListenerManager;
import dev.gameengine.monitoring.EventsJFR;
import dev.gameengine.monitoring.TickMonitor;
import dev.gameengine.network.ConnectionManager;
import dev.gameengine.network.packet.PacketParser;
import dev.gameengine.network.packet.PacketVanilla;
import dev.gameengine.network.socket.Server;
import dev.gameengine.registry.Registries;
import dev.gameengine.thread.Acquirable;
import dev.gameengine.thread.ThreadDispatcher;
import dev.gameengine.thread.ThreadProvider;
import dev.gameengine.thread.TickThread;
import dev.gameengine.timer.SchedulerManager;
import dev.gameengine.network.packet.server.common.PluginMessagePacket;
import dev.gameengine.network.packet.server.play.ServerDifficultyPacket;
import dev.gameengine.network.PacketSendingUtils;
import dev.gameengine.network.PacketViewableUtils;
import dev.gameengine.timer.Tick;
import dev.gameengine.utils.validate.Check;
import dev.gameengine.world.Difficulty;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ServerProcessImpl implements ServerProcess, Registries.Delegating {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerProcessImpl.class);
    private static final long SHUTDOWN_JOIN_TIMEOUT_MS = 5_000;

    private final Auth auth;
    private final ServerConfig config;

    private final ExceptionManager exception;
    private final Registries registries;

    private final ConnectionManager connection;
    private final PacketListenerManager packetListener;
    private final PacketParser.Client packetParser;
    private final InstanceManager instance;
    private final BlockManager block;
    private final CommandManager command;
    private final GlobalEventHandler eventHandler;
    private final SchedulerManager scheduler;
    private final BossBarManager bossBar;
    private final ClickCallbackManager clickCallbackManager;
    private final Audiences audiences;

    private final Server server;

    private final ThreadDispatcher<Chunk, Entity> dispatcher;
    private final PacketViewableUtils viewablePackets;
    private final Ticker ticker;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();

    private volatile String brandName = "game-engine";
    private volatile Difficulty difficulty = Difficulty.NORMAL;
    private volatile int compressionThreshold = 256;
    // Set by the ticker itself, so that stop() can wait for the in-flight tick to complete.
    private volatile @Nullable TickSchedulerThread tickSchedulerThread;

    public ServerProcessImpl(Auth auth, ServerConfig config) {
        this.auth = auth;
        this.config = config;
        this.exception = new ExceptionManager(this::stop);
        this.registries = Registries.vanilla();

        this.connection = new ConnectionManager(this);
        this.packetListener = new PacketListenerManager(this);
        this.packetParser = PacketVanilla.CLIENT_PACKET_PARSER;
        this.instance = new InstanceManager(this);
        this.block = new BlockManager();
        this.command = new CommandManager(this);
        this.eventHandler = new GlobalEventHandler(exception);
        this.scheduler = new SchedulerManager(exception);
        this.bossBar = new BossBarManager();
        this.clickCallbackManager = new ClickCallbackManager();
        this.audiences = new Audiences(this);

        this.server = new Server(this, packetParser);
        Registries.tagChangeListener(registries, connection::invalidateTags);

        this.dispatcher = ThreadDispatcher.dispatcher(ThreadProvider.counter(), config.dispatcherThreads(),
                exception::handleException);
        this.viewablePackets = new PacketViewableUtils(this, config.viewablePacket(), config.pooledBufferSize());
        this.ticker = new TickerImpl();
    }

    @Override
    public Auth auth() {
        return auth;
    }

    @Override
    public ServerConfig config() {
        return config;
    }

    @Override
    public ExceptionManager exception() {
        return exception;
    }

    @Override
    public Registries registries() {
        return registries;
    }


    @Override
    public ConnectionManager connection() {
        return connection;
    }

    @Override
    public InstanceManager instance() {
        return instance;
    }

    @Override
    public BlockManager block() {
        return block;
    }

    @Override
    public CommandManager command() {
        return command;
    }

    @Override
    public GlobalEventHandler eventHandler() {
        return eventHandler;
    }

    @Override
    public SchedulerManager scheduler() {
        return scheduler;
    }

    @Override
    public BossBarManager bossBar() {
        return bossBar;
    }

    @Override
    public PacketListenerManager packetListener() {
        return packetListener;
    }

    @Override
    public PacketParser.Client packetParser() {
        return packetParser;
    }

    @Override
    public Server server() {
        return server;
    }

    @Override
    public ThreadDispatcher<Chunk, Entity> dispatcher() {
        return dispatcher;
    }

    @Override
    public PacketViewableUtils viewablePackets() {
        return viewablePackets;
    }

    @Override
    public Ticker ticker() {
        return ticker;
    }

    @Override
    public ClickCallbackManager clickCallbackManager() {
        return clickCallbackManager;
    }

    @Override
    public Audiences audiences() {
        return audiences;
    }

    @Override
    public String brandName() {
        return brandName;
    }

    @Override
    public void setBrandName(String brandName) {
        this.brandName = brandName;
        PacketSendingUtils.broadcastPlayPacket(this, PluginMessagePacket.brandPacket(brandName));
    }

    @Override
    public Difficulty difficulty() {
        return difficulty;
    }

    @Override
    public void setDifficulty(Difficulty difficulty) {
        this.difficulty = difficulty;
        PacketSendingUtils.broadcastPlayPacket(this, new ServerDifficultyPacket(difficulty, true));
    }

    @Override
    public int compressionThreshold() {
        return compressionThreshold;
    }

    @Override
    public void setCompressionThreshold(int compressionThreshold) {
        Check.stateCondition(isAlive(), "The compression threshold cannot be changed after the server has been started.");
        this.compressionThreshold = compressionThreshold;
    }

    @Override
    public void start(SocketAddress socketAddress) {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Server already started");
        }

        final String brand = brandName;
        LOGGER.info("Starting {} ({}) server.", brand, Git.version());
        switch (auth) {
            case Auth.Offline _ ->
                    LOGGER.info("Running in offline mode. Beware that this is not secure and players can impersonate each other.");
            case Auth.Velocity _ -> LOGGER.info("Running in Velocity mode with modern IP forwarding.");
            case Auth.Bungee bungee -> {
                if (bungee.guard()) {
                    LOGGER.info("Running in BungeeCord mode, using legacy IP forwarding with Guard enabled.");
                } else {
                    LOGGER.info("Running in BungeeCord mode without BungeeGuard. Be sure to configure your firewall to prevent direct connections.");
                }
            }
        }

        // Init server
        try {
            server.init(socketAddress);
        } catch (IOException e) {
            exception.handleException(e);
            throw new RuntimeException(e);
        }

        // Registries are part of the handshake answer, they must not change once clients can connect.
        // Tests keep them writable, they register their own entries after starting a process.
        if (!config.registryUnsafeOps() && !config.insideTest()) Registries.freeze(registries);

        // Start server
        server.start();

        LOGGER.info("{} server started successfully.", brand);

        // Stop the server on SIGINT
        if (config.shutdownOnSignal()) Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    @Override
    public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        // The shutdown sequence waits for every tick thread to exit, which can never happen while the
        // caller is one of them (e.g. a /stop command running on a TickThread, upstream issue #2907).
        final Thread caller = Thread.currentThread();
        if (caller instanceof TickThread || caller instanceof TickSchedulerThread) {
            new Thread(this::shutdownProcess, "Ge-Shutdown").start();
        } else {
            shutdownProcess();
        }
    }

    private void shutdownProcess() {
        final String brand = brandName;
        LOGGER.info("Stopping {} server.", brand);
        // `stopped` is already set, so the ticker loop exits once the tick in flight is over.
        // Wait for it before touching anything else, otherwise the shutdown races with ticking.
        joinThread(tickSchedulerThread);
        LOGGER.info("Shutting down all thread pools.");
        dispatcher.shutdown();
        for (TickThread tickThread : dispatcher.threads()) joinThread(tickThread);
        // Nothing is ticking anymore, user shutdown tasks can safely run before players are kicked.
        scheduler.shutdown();
        connection.shutdown();
        server.stop();
        LOGGER.info("{} server stopped successfully.", brand);
    }

    private static void joinThread(@Nullable Thread thread) {
        if (thread == null || thread == Thread.currentThread() || !thread.isAlive()) return;
        try {
            thread.join(SHUTDOWN_JOIN_TIMEOUT_MS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return;
        }
        if (thread.isAlive())
            LOGGER.warn("Thread {} did not stop within {}ms.", thread.getName(), SHUTDOWN_JOIN_TIMEOUT_MS);
    }

    @Override
    public boolean isAlive() {
        return started.get() && !stopped.get();
    }

    private final class TickerImpl implements Ticker {
        @Override
        public void tick(long nanoTime) {
            if (Thread.currentThread() instanceof TickSchedulerThread self) tickSchedulerThread = self;
            var serverTickEvent = EventsJFR.newServerTick();
            serverTickEvent.begin();
            scheduler().processTick();

            // Connection tick (let waiting clients in, send keep alives, handle configuration players packets)
            try {
                connection().tick(nanoTime);
            } catch (Exception e) {
                // A single misbehaving connection must not skip the rest of the tick
                exception().handleException(e);
            }

            // Server tick (chunks/entities)
            serverTick(nanoTime);

            // The click callback provider needs ticking to clean up the cache.
            clickCallbackManager().tick(nanoTime);

            scheduler().processTickEnd();

            // Flush all waiting packets
            viewablePackets.flush();

            // Monitoring
            {
                final double acquisitionTimeMs = Acquirable.resetAcquiringTime() / 1e6D;
                final double tickTimeMs = (System.nanoTime() - nanoTime) / 1e6D;
                final TickMonitor tickMonitor = new TickMonitor(tickTimeMs, acquisitionTimeMs);
                eventHandler().call(new ServerTickMonitorEvent(tickMonitor));
            }
            serverTickEvent.commit();
        }

        private void serverTick(long nanoStart) {
            long milliStart = TimeUnit.NANOSECONDS.toMillis(nanoStart);
            // Tick all instances
            for (Instance instance : instance().getInstances()) {
                try {
                    instance.tick(milliStart);
                } catch (Exception e) {
                    exception().handleException(e);
                }
            }
            // Tick all chunks (and entities inside)
            dispatcher().updateAndAwait(nanoStart);

            // Clear removed entities & update threads
            // the nanoTimeout for refreshThreads is the remaining tick duration
            final long tickDuration = System.nanoTime() - nanoStart;
            final long remainingTickDuration = Math.max(0, Tick.SERVER_TICKS.getDuration().toNanos() - tickDuration);
            dispatcher().refreshThreads(remainingTickDuration);
        }
    }
}
