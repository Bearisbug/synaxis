package io.github.bearisbug.synaxis;

import io.github.bearisbug.synaxis.adventure.ClickCallbackManager;
import io.github.bearisbug.synaxis.adventure.audience.Audiences;
import io.github.bearisbug.synaxis.adventure.bossbar.BossBarManager;
import io.github.bearisbug.synaxis.command.CommandManager;
import io.github.bearisbug.synaxis.entity.Entity;
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
     * The packet compression threshold, {@code 0} means that compression is disabled.
     */
    int compressionThreshold();

    /**
     * Changes the compression threshold. Must be called before {@link #start(SocketAddress)}.
     *
     * @throws IllegalStateException if the process has already been started
     */
    void setCompressionThreshold(int compressionThreshold);

    void start(SocketAddress socketAddress);

    void stop();

    boolean isAlive();

    @ApiStatus.NonExtendable
    interface Ticker {
        void tick(long nanoTime);
    }
}
