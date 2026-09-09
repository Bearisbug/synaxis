package dev.gameengine;

import dev.gameengine.adventure.ClickCallbackManager;
import dev.gameengine.adventure.audience.Audiences;
import dev.gameengine.adventure.bossbar.BossBarManager;
import dev.gameengine.command.CommandManager;
import dev.gameengine.entity.Entity;
import dev.gameengine.event.GlobalEventHandler;
import dev.gameengine.exception.ExceptionManager;
import dev.gameengine.instance.Chunk;
import dev.gameengine.instance.InstanceManager;
import dev.gameengine.instance.block.BlockManager;
import dev.gameengine.instance.block.rule.BlockPlacementRule;
import dev.gameengine.listener.manager.PacketListenerManager;
import dev.gameengine.network.ConnectionManager;
import dev.gameengine.network.packet.PacketParser;
import dev.gameengine.network.socket.Server;
import dev.gameengine.registry.Registries;
import dev.gameengine.thread.ThreadDispatcher;
import dev.gameengine.timer.SchedulerManager;
import dev.gameengine.utils.PacketViewableUtils;
import dev.gameengine.world.Difficulty;
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
     * Handles {@link dev.gameengine.instance.block.BlockHandler block handlers}
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
