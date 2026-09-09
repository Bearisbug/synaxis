package net.minestom.server;

import net.minestom.server.adventure.ClickCallbackManager;
import net.minestom.server.adventure.bossbar.BossBarManager;
import net.minestom.server.command.CommandManager;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.server.ServerTickMonitorEvent;
import net.minestom.server.exception.ExceptionManager;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.block.BlockManager;
import net.minestom.server.listener.manager.PacketListenerManager;
import net.minestom.server.monitoring.EventsJFR;
import net.minestom.server.monitoring.TickMonitor;
import net.minestom.server.network.ConnectionManager;
import net.minestom.server.network.packet.PacketParser;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.socket.Server;
import net.minestom.server.registry.Registries;
import net.minestom.server.thread.Acquirable;
import net.minestom.server.thread.ThreadDispatcher;
import net.minestom.server.thread.ThreadProvider;
import net.minestom.server.thread.TickSchedulerThread;
import net.minestom.server.thread.TickThread;
import net.minestom.server.timer.SchedulerManager;
import net.minestom.server.utils.PacketViewableUtils;
import net.minestom.server.utils.time.Tick;
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

    private final Server server;

    private final ThreadDispatcher<Chunk, Entity> dispatcher;
    private final Ticker ticker;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    // Set by the ticker itself, so that stop() can wait for the in-flight tick to complete.
    private volatile @Nullable TickSchedulerThread tickSchedulerThread;

    public ServerProcessImpl(Auth auth) {
        this.auth = auth;
        this.exception = new ExceptionManager();
        this.registries = Registries.vanilla();

        this.connection = new ConnectionManager();
        this.packetListener = new PacketListenerManager();
        this.packetParser = PacketVanilla.CLIENT_PACKET_PARSER;
        this.instance = new InstanceManager(this);
        this.block = new BlockManager();
        this.command = new CommandManager();
        this.eventHandler = new GlobalEventHandler();
        this.scheduler = new SchedulerManager();
        this.bossBar = new BossBarManager();
        this.clickCallbackManager = new ClickCallbackManager();

        this.server = new Server(packetParser);

        this.dispatcher = ThreadDispatcher.dispatcher(ThreadProvider.counter(), ServerFlag.DISPATCHER_THREADS);
        this.ticker = new TickerImpl();
    }

    @Override
    public Auth auth() {
        return auth;
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
    public Ticker ticker() {
        return ticker;
    }

    @Override
    public ClickCallbackManager clickCallbackManager() {
        return clickCallbackManager;
    }

    @Override
    public void start(SocketAddress socketAddress) {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Server already started");
        }

        final String brand = MinecraftServer.getBrandName();
        LOGGER.info("Starting {} ({}) server.", brand, Git.version());
        switch (auth) {
            case Auth.Offline _ ->
                    LOGGER.info("Running in offline mode. Beware that this is not secure and players can impersonate each other.");
            case Auth.Online _ -> LOGGER.info("Running in online mode with Mojang's authentication.");
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

        // Start server
        server.start();

        LOGGER.info("{} server started successfully.", brand);

        // Stop the server on SIGINT
        if (ServerFlag.SHUTDOWN_ON_SIGNAL) Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    @Override
    public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        // The shutdown sequence waits for every tick thread to exit, which can never happen while the
        // caller is one of them (e.g. a /stop command running on a TickThread, upstream issue #2907).
        final Thread caller = Thread.currentThread();
        if (caller instanceof TickThread || caller instanceof TickSchedulerThread) {
            new Thread(this::shutdownProcess, "Ms-Shutdown").start();
        } else {
            shutdownProcess();
        }
    }

    private void shutdownProcess() {
        final String brand = MinecraftServer.getBrandName();
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
            PacketViewableUtils.flush();

            // Monitoring
            {
                final double acquisitionTimeMs = Acquirable.resetAcquiringTime() / 1e6D;
                final double tickTimeMs = (System.nanoTime() - nanoTime) / 1e6D;
                final TickMonitor tickMonitor = new TickMonitor(tickTimeMs, acquisitionTimeMs);
                EventDispatcher.call(new ServerTickMonitorEvent(tickMonitor));
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
