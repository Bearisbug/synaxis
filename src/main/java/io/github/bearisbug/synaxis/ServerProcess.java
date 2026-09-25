package io.github.bearisbug.synaxis;

import io.github.bearisbug.synaxis.adventure.ClickCallbackManager;
import io.github.bearisbug.synaxis.adventure.audience.Audiences;
import io.github.bearisbug.synaxis.adventure.bossbar.BossBarManager;
import io.github.bearisbug.synaxis.command.CommandManager;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.TrackingRanges;
import io.github.bearisbug.synaxis.event.GlobalEventHandler;
import io.github.bearisbug.synaxis.exception.ExceptionManager;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.InstanceManager;
import io.github.bearisbug.synaxis.instance.block.BlockManager;
import io.github.bearisbug.synaxis.instance.block.rule.BlockPlacementRule;
import io.github.bearisbug.synaxis.listener.manager.PacketListenerManager;
import io.github.bearisbug.synaxis.network.ConnectionManager;
import io.github.bearisbug.synaxis.network.packet.PacketParser;
import io.github.bearisbug.synaxis.network.socket.Server;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.thread.ThreadDispatcher;
import io.github.bearisbug.synaxis.timer.SchedulerManager;
import io.github.bearisbug.synaxis.network.PacketViewableUtils;
import io.github.bearisbug.synaxis.world.Difficulty;
import org.jetbrains.annotations.ApiStatus;

import java.net.SocketAddress;
import java.time.Duration;

@ApiStatus.NonExtendable
public interface ServerProcess extends Registries {
    /**
     * Creates a new server process. Several processes can coexist in the same JVM, they share
     * nothing but the static registries.
     */
    static ServerProcess of(Auth auth) {
        return of(auth, ServerConfig.fromSystemProperties());
    }

    /**
     * Creates a new server process with an explicit configuration. Processes sharing a JVM keep
     * their own settings.
     */
    static ServerProcess of(Auth auth, ServerConfig config) {
        return new ServerProcessImpl(auth, config);
    }

    /**
     * Creates a new server process running in offline mode.
     */
    static ServerProcess of() {
        return of(new Auth.Offline());
    }

    /**
     * Creates a new server process running in offline mode with an explicit configuration.
     */
    static ServerProcess of(ServerConfig config) {
        return of(new Auth.Offline(), config);
    }

    /**
     * The settings of this process.
     */
    ServerConfig config();

    /**
     * Gets the registries owned by this process.
     *
     * @return the process registries
     */
    Registries registries();

    Auth auth();

    /**
     * Handles incoming connections/players.
     */
    ConnectionManager connection();

    /**
     * Handles registered instances.
     */
    InstanceManager instance();

    /**
     * Handles {@link io.github.bearisbug.synaxis.instance.block.BlockHandler block handlers}
     * and {@link BlockPlacementRule placement rules}.
     */
    BlockManager block();

    /**
     * Handles registered commands.
     */
    CommandManager command();

    /**
     * Gets the global event handler.
     * <p>
     * Used to register event callback at a global scale.
     */
    GlobalEventHandler eventHandler();

    /**
     * Main scheduler ticked at the server rate.
     */
    SchedulerManager scheduler();

    /**
     * Handles registered boss bars.
     */
    BossBarManager bossBar();

    /**
     * Handles all thrown exceptions from the server.
     */
    ExceptionManager exception();

    /**
     * Handles incoming packets.
     */
    PacketListenerManager packetListener();

    /**
     * Gets the object handling the client packets parsing.
     * <p>
     * Can be used if you want to convert a buffer to a client packet object.
     */
    PacketParser.Client packetParser();

    /**
     * Exposed socket server.
     */
    Server server();

    /**
     * Dispatcher for tickable game objects.
     */
    ThreadDispatcher<Chunk, Entity> dispatcher();

    /**
     * Buffers the packets sent to every viewer of a {@link Viewable} until the end of the tick.
     */
    @ApiStatus.Internal
    PacketViewableUtils viewablePackets();

    /**
     * Handles the server ticks.
     */
    Ticker ticker();

    /**
     * The tick watchdog of this process, which the host starts and stops.
     */
    @ApiStatus.Internal
    Watchdog watchdog();

    /**
     * The click callback manager.
     */
    ClickCallbackManager clickCallbackManager();

    /**
     * Adventure audiences backed by this process.
     */
    Audiences audiences();

    /**
     * The default tracking range of each entity type ({@code docs/DESIGN-PROD.md} {@code API-PROD-019}).
     */
    TrackingRanges trackingRanges();

    /**
     * The server brand name shown by the client (F3 screen).
     */
    String brandName();

    /**
     * Changes the server brand name and sends the change to all connected players.
     */
    void setBrandName(String brandName);

    /**
     * The server difficulty shown in the game options.
     */
    Difficulty difficulty();

    /**
     * Changes the server difficulty and sends the appropriate packet to all connected clients.
     */
    void setDifficulty(Difficulty difficulty);

    /**
     * The packet compression threshold, {@code 0} means that compression is disabled. Starts at
     * {@link ServerConfig#compressionThreshold()}.
     */
    int compressionThreshold();

    /**
     * Changes the compression threshold. Must be called before {@link #start(SocketAddress)}.
     *
     * @throws IllegalStateException if the process has already been started
     */
    void setCompressionThreshold(int compressionThreshold);

    void start(SocketAddress socketAddress);

    /**
     * Shuts down without a countdown, the same as {@code beginShutdown(Duration.ZERO)}.
     */
    void stop();

    /**
     * Starts the shutdown sequence of {@code docs/DESIGN-PROD.md} §11.4: close the login gate, count
     * down, save through the {@link ShutdownParticipant}, kick everyone, disable the plugins, stop
     * ticking. The tick keeps running until the plugins are disabled. A second call returns at once.
     * <p>
     * Called from a tick thread, the sequence runs on a thread of its own and this returns at once;
     * called from anywhere else, it returns once the process has stopped.
     *
     * @param countdown how long to wait between closing the gate and saving
     */
    void beginShutdown(Duration countdown);

    /**
     * Registers the host side of the save phase. Only once, and only before {@link #start}; without
     * one the save phase ends at once.
     *
     * @param participant the host's save
     * @throws IllegalStateException if one is already registered or the process has started
     */
    void setShutdownParticipant(ShutdownParticipant participant);

    /**
     * Drives the shutdown sequence after the tick watchdog judged the process dead
     * ({@code docs/DESIGN-PROD.md} §11.4 and §11.5): before the save phase it skips the rest of
     * the countdown and saves with {@code tickAlive=false} and a 15 s budget; during it, it cuts the
     * budget to 15 s from now; after it, it does nothing. Called on the watchdog report thread.
     *
     * @return true if the sequence ran to {@link ShutdownPhase#STOPPED} on this thread
     */
    @ApiStatus.Internal
    boolean shutdownAfterWatchdogDeath();

    /**
     * Where the shutdown sequence of {@code docs/DESIGN-PROD.md} §11.4 stands.
     */
    @ApiStatus.Internal
    ShutdownPhase shutdownPhase();

    boolean isAlive();

    /**
     * The phases of the shutdown sequence, {@code docs/DESIGN-PROD.md} §11.4.
     */
    @ApiStatus.Internal
    enum ShutdownPhase {
        /** Not shutting down. */
        RUNNING,
        /** New logins are turned away; the countdown runs. */
        GATE_CLOSED,
        /** The participant saves. */
        SAVING,
        /** Every connection is being kicked while the tick still runs. */
        KICKING,
        /** The plugins are being disabled. */
        PLUGINS_DISABLED,
        /** The tick has stopped. */
        STOPPED
    }

    @ApiStatus.NonExtendable
    interface Ticker {
        void tick(long nanoTime);
    }
}
