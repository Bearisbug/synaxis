package dev.gameengine;

import dev.gameengine.adventure.ClickCallbackManager;
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
import org.jetbrains.annotations.ApiStatus;

import java.net.SocketAddress;

@ApiStatus.NonExtendable
public interface ServerProcess extends Registries {
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
     * Handles the server ticks.
     */
    Ticker ticker();

    /**
     * The click callback manager.
     */
    ClickCallbackManager clickCallbackManager();

    void start(SocketAddress socketAddress);

    void stop();

    boolean isAlive();

    @ApiStatus.NonExtendable
    interface Ticker {
        void tick(long nanoTime);
    }
}
