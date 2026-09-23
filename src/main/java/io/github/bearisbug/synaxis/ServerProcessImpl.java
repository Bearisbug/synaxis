package io.github.bearisbug.synaxis;

import io.github.bearisbug.synaxis.adventure.ClickCallbackManager;
import io.github.bearisbug.synaxis.adventure.audience.Audiences;
import io.github.bearisbug.synaxis.adventure.bossbar.BossBarManager;
import io.github.bearisbug.synaxis.command.CommandManager;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

final class ServerProcessImpl implements ServerProcess, Registries.Delegating {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerProcessImpl.class);
    private static final long SHUTDOWN_JOIN_TIMEOUT_MS = 5_000;
    /** The save phase budget of {@code docs/DESIGN-PROD.md} §11.4 while the tick runs. */
    private static final long SAVE_BUDGET_SECONDS = 30;
    /** The save phase budget once the watchdog judged the process dead: its halt comes 15 s later. */
    private static final long DEATH_SAVE_BUDGET_SECONDS = 15;
    private static final long SAVE_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    /** How long the kicked players in play get to leave the world, §11.4 {@code kicking}. */
    private static final long KICK_BUDGET_SECONDS = 5;
    private static final long KICK_POLL_MILLIS = 20;
    private static final long SHUTDOWN_TASKS_PICKUP_MILLIS = 1_000;
    private static final long SHUTDOWN_TASKS_BUDGET_SECONDS = 15;

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
    // Set by the first beginShutdown; `stopped` only follows at the end of the sequence (§11.4)
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final CompletableFuture<Void> watchdogDeath = new CompletableFuture<>();
    private volatile ShutdownPhase shutdownPhase = ShutdownPhase.RUNNING;
    private volatile @Nullable ShutdownParticipant shutdownParticipant;

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
        beginShutdown(Duration.ZERO);
    }

    @Override
    public void beginShutdown(Duration countdown) {
        if (countdown.isNegative()) throw new IllegalArgumentException("countdown " + countdown + " is negative");
        if (!stopping.compareAndSet(false, true)) return;
        // The shutdown sequence waits for every tick thread to exit, which can never happen while the
        // caller is one of them (e.g. a /stop command running on a TickThread, upstream issue #2907).
        final Thread caller = Thread.currentThread();
        if (caller instanceof TickThread || caller instanceof TickSchedulerThread) {
            new Thread(() -> shutdownProcess(countdown, false), "Syn-Shutdown").start();
        } else {
            shutdownProcess(countdown, false);
        }
    }

    @Override
    public void setShutdownParticipant(ShutdownParticipant participant) {
        Check.stateCondition(started.get(), "The shutdown participant is set before the server starts.");
        Check.stateCondition(this.shutdownParticipant != null, "A shutdown participant is already set.");
        this.shutdownParticipant = participant;
    }

    @Override
    public boolean shutdownAfterWatchdogDeath() {
        watchdogDeath.complete(null);
        // Not shutting down yet: this thread runs the whole sequence, without a countdown
        if (stopping.compareAndSet(false, true)) {
            shutdownProcess(Duration.ZERO, true);
            return true;
        }
        // Otherwise the thread already running the sequence picks the death up: the countdown ends,
        // or the save phase shortens its budget. Past the save phase there is nothing to change.
        return false;
    }

    @Override
    public ShutdownPhase shutdownPhase() {
        return shutdownPhase;
    }

    /**
     * The sequence of {@code docs/DESIGN-PROD.md} §11.4. {@code stopped} is set in the last step
     * only, so the tick keeps running while players are saved and kicked and plugins disabled.
     *
     * @param countdown how long the gate stays closed before the save phase
     * @param dying     whether the watchdog already judged the process dead
     */
    private void shutdownProcess(Duration countdown, boolean dying) {
        final String brand = brandName;
        LOGGER.info("Stopping {} server.", brand);
        final ShutdownParticipant participant = this.shutdownParticipant;
        try {
            // gate_closed: the login gate turns new logins away from here on
            this.shutdownPhase = ShutdownPhase.GATE_CLOSED;
            connection.gate().close();
            boolean dead = dying;
            if (!dead) {
                if (participant != null) callParticipant(() -> participant.onGateClosed(countdown));
                dead = awaitCountdown(countdown);
            }
            this.shutdownPhase = ShutdownPhase.SAVING;
            save(participant, dead);
            // kicking: the tick still runs, which is what fires PlayerDisconnectEvent for them
            this.shutdownPhase = ShutdownPhase.KICKING;
            kickEveryone();
            // plugins_disabled: the shutdown tasks, the host's plugin onDisable among them
            this.shutdownPhase = ShutdownPhase.PLUGINS_DISABLED;
            runShutdownTasks();
            // stopped: the ticker loop exits once the tick in flight is over
            stopped.set(true);
            joinThread(tickSchedulerThread);
            LOGGER.info("Shutting down all thread pools.");
            dispatcher.shutdown();
            for (TickThread tickThread : dispatcher.threads()) joinThread(tickThread);
            server.stop();
            this.shutdownPhase = ShutdownPhase.STOPPED;
        } finally {
            stopped.set(true);
            // Whatever happened above, the JVM must stop referencing this process through the hook.
            removeShutdownHook();
        }
        LOGGER.info("{} server stopped successfully.", brand);
    }

    /**
     * @return true if the watchdog judged the process dead before the countdown ran out
     */
    private boolean awaitCountdown(Duration countdown) {
        if (countdown.isZero()) return watchdogDeath.isDone();
        try {
            watchdogDeath.get(countdown.toNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException _) {
            return false;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException _) {
            return true;
        }
    }

    /**
     * The save phase: {@value #SAVE_BUDGET_SECONDS} s while the tick runs, {@value #DEATH_SAVE_BUDGET_SECONDS} s
     * once the watchdog judged the process dead, which can also happen while this runs.
     */
    private void save(@Nullable ShutdownParticipant participant, boolean dying) {
        if (participant == null) return;
        boolean dead = dying;
        final long budget = TimeUnit.SECONDS.toNanos(dead ? DEATH_SAVE_BUDGET_SECONDS : SAVE_BUDGET_SECONDS);
        long deadline = System.nanoTime() + budget;
        CompletableFuture<?> saved = startSave(participant, Duration.ofNanos(budget), !dead);
        while (!saved.isDone()) {
            final long now = System.nanoTime();
            if (now - deadline >= 0) break;
            if (!dead && watchdogDeath.isDone()) {
                // saving → saving: the tick will not come back, so the rest is read on this thread
                dead = true;
                deadline = Math.min(deadline, now + TimeUnit.SECONDS.toNanos(DEATH_SAVE_BUDGET_SECONDS));
                saved = startSave(participant, Duration.ofNanos(deadline - now), false);
                continue;
            }
            try {
                saved.get(Math.min(deadline - now, SAVE_POLL_NANOS), TimeUnit.NANOSECONDS);
            } catch (TimeoutException | ExecutionException _) {
                // Looked at again above; a failed save is as over as a completed one
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        final boolean timedOut = !saved.isDone();
        if (timedOut) LOGGER.error("The shutdown save did not complete within its budget, going on with the shutdown.");
        callParticipant(() -> participant.onSaveEnded(timedOut));
    }

    private CompletableFuture<?> startSave(ShutdownParticipant participant, Duration budget, boolean tickAlive) {
        try {
            return participant.saveAll(budget, tickAlive);
        } catch (Throwable throwable) {
            exception.handleException(throwable);
            return CompletableFuture.completedFuture(null);
        }
    }

    private void callParticipant(Runnable call) {
        try {
            call.run();
        } catch (Throwable throwable) {
            exception.handleException(throwable);
        }
    }

    /**
     * Kicks every connection and waits, at most {@value #KICK_BUDGET_SECONDS} s, for the players in
     * play to leave the world: a kick only schedules {@code Entity#remove} for their next tick, and
     * that removal is what fires {@code PlayerDisconnectEvent}.
     */
    private void kickEveryone() {
        final List<Player> inPlay = List.copyOf(connection.getOnlinePlayers());
        connection.shutdown();
        if (tickSchedulerThread == null) {
            // A process that never started its ticker (tests tick it by hand) gets one last dispatcher
            // update instead. It runs arbitrary user code, so it is bounded like every other wait
            // here: a wedged element must not keep stop() from returning.
            if (dispatcher.isAlive()) {
                final Thread lastTick = new Thread(() -> dispatcher.updateAndAwait(System.nanoTime()),
                        "Syn-Shutdown-Tick");
                lastTick.setDaemon(true);
                lastTick.start();
                joinThread(lastTick);
            }
            return;
        }
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(KICK_BUDGET_SECONDS);
        while (inPlay.stream().anyMatch(player -> !player.isRemoved()) && System.nanoTime() - deadline < 0) {
            try {
                Thread.sleep(KICK_POLL_MILLIS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Runs the shutdown tasks on the tick scheduler thread, between two ticks, so that a plugin's
     * {@code onDisable} never runs alongside a tick. A tick scheduler that does not pick them up
     * within {@value #SHUTDOWN_TASKS_PICKUP_MILLIS} ms is stuck, and this thread runs them instead.
     */
    private void runShutdownTasks() {
        final AtomicBoolean claimed = new AtomicBoolean();
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final Runnable drain = () -> {
            if (!claimed.compareAndSet(false, true)) return;
            try {
                scheduler.shutdown();
            } finally {
                done.complete(null);
            }
        };
        final TickSchedulerThread ticker = tickSchedulerThread;
        if (ticker != null && ticker.isAlive() && !watchdogDeath.isDone()) {
            scheduler.scheduleNextTick(drain);
            try {
                done.get(SHUTDOWN_TASKS_PICKUP_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException _) {
                // Not picked up in time, or picked up and still running: handled below
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
        drain.run();
        try {
            done.get(SHUTDOWN_TASKS_BUDGET_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException _) {
            LOGGER.warn("The shutdown tasks did not complete within {} s.", SHUTDOWN_TASKS_BUDGET_SECONDS);
        } catch (ExecutionException _) {
            // Cannot happen, the future is only ever completed normally
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
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
