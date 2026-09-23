package io.github.bearisbug.synaxis;

import io.github.bearisbug.synaxis.adventure.ClickCallbackManager;
import io.github.bearisbug.synaxis.adventure.audience.Audiences;
import io.github.bearisbug.synaxis.adventure.bossbar.BossBarManager;
import io.github.bearisbug.synaxis.command.CommandManager;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.event.GlobalEventHandler;
import io.github.bearisbug.synaxis.event.server.ServerTickMonitorEvent;
import io.github.bearisbug.synaxis.exception.ExceptionManager;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.InstanceManager;
import io.github.bearisbug.synaxis.instance.block.BlockManager;
import io.github.bearisbug.synaxis.listener.manager.PacketListenerManager;
import io.github.bearisbug.synaxis.monitoring.EventsJFR;
import io.github.bearisbug.synaxis.monitoring.TickMonitor;
import io.github.bearisbug.synaxis.network.ConnectionManager;
import io.github.bearisbug.synaxis.network.packet.PacketParser;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.socket.Server;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.thread.Acquirable;
import io.github.bearisbug.synaxis.thread.ThreadDispatcher;
import io.github.bearisbug.synaxis.thread.ThreadProvider;
import io.github.bearisbug.synaxis.thread.TickThread;
import io.github.bearisbug.synaxis.timer.SchedulerManager;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ServerDifficultyPacket;
import io.github.bearisbug.synaxis.network.PacketSendingUtils;
import io.github.bearisbug.synaxis.network.PacketViewableUtils;
import io.github.bearisbug.synaxis.timer.Tick;
import io.github.bearisbug.synaxis.utils.validate.Check;
import io.github.bearisbug.synaxis.world.Difficulty;
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
    private final Watchdog watchdog;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();

    private volatile String brandName = "synaxis";
    private volatile Difficulty difficulty = Difficulty.NORMAL;
    private volatile int compressionThreshold = 256;
    // Set by the ticker itself, so that stop() can wait for the in-flight tick to complete.
    private volatile @Nullable TickSchedulerThread tickSchedulerThread;
    // Kept so that stop() can unregister it, the JVM strongly references every registered hook.
    volatile @Nullable Thread shutdownHook;

    public ServerProcessImpl(Auth auth, ServerConfig config) {
        this.auth = auth;
        this.config = config;
        this.exception = new ExceptionManager();
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
        this.watchdog = new Watchdog(config, dispatcher::threads);
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
    public Watchdog watchdog() {
        return watchdog;
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
            case Auth.Offline offline -> LOGGER.info("Running in offline mode{}. Beware that this is not secure "
                    + "and players can impersonate each other.", offline.encrypt() ? " with encryption" : "");
            case Auth.Online online -> LOGGER.info("Running in online mode, logins are verified against {}.",
                    online.sessionServer().hasJoinedUrl());
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
        if (config.shutdownOnSignal()) {
            final Thread hook = new Thread(this::stop);
            Runtime.getRuntime().addShutdownHook(hook);
            this.shutdownHook = hook;
        }
    }

    @Override
    public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        // The shutdown sequence waits for every tick thread to exit, which can never happen while the
        // caller is one of them (e.g. a /stop command running on a TickThread, upstream issue #2907).
        final Thread caller = Thread.currentThread();
        if (caller instanceof TickThread || caller instanceof TickSchedulerThread) {
            new Thread(this::shutdownProcess, "Syn-Shutdown").start();
        } else {
            shutdownProcess();
        }
    }

    private void shutdownProcess() {
        final String brand = brandName;
        LOGGER.info("Stopping {} server.", brand);
        try {
            // `stopped` is already set, so the ticker loop exits once the tick in flight is over.
            // Wait for it before touching anything else, otherwise the shutdown races with ticking.
            joinThread(tickSchedulerThread);
            // No tick is in flight anymore, user shutdown tasks can safely run before players are kicked.
            scheduler.shutdown();
            connection.shutdown();
            // Kicking a PLAY player only schedules Entity#remove on its next tick, which is where
            // PlayerDisconnectEvent is fired. The tick threads are still up for that one last tick.
            // It runs arbitrary user code, so it is bounded like every other wait here: a wedged
            // element must not keep stop() from returning.
            if (dispatcher.isAlive()) {
                final Thread lastTick = new Thread(() -> dispatcher.updateAndAwait(System.nanoTime()),
                        "Syn-Shutdown-Tick");
                lastTick.setDaemon(true);
                lastTick.start();
                joinThread(lastTick);
            }
            LOGGER.info("Shutting down all thread pools.");
            dispatcher.shutdown();
            for (TickThread tickThread : dispatcher.threads()) joinThread(tickThread);
            server.stop();
        } finally {
            // Whatever happened above, the JVM must stop referencing this process through the hook.
            removeShutdownHook();
        }
        LOGGER.info("{} server stopped successfully.", brand);
    }

    private void removeShutdownHook() {
        final Thread hook = this.shutdownHook;
        if (hook == null) return;
        this.shutdownHook = null;
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException _) {
            // The JVM is already shutting down, which is exactly when the hook runs this method.
        }
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
            final long watched = watchdog.tickStarted();
            try {
                tickWatched(nanoTime);
            } finally {
                watchdog.tickEnded(watched);
            }
        }

        private void tickWatched(long nanoTime) {
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
