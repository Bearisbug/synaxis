package io.github.bearisbug.synaxis.monitoring;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Timespan;
import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

/**
 * JFR events for monitoring synaxis server activities.
 */
@ApiStatus.Internal
@SuppressWarnings({"ALL", "unused"})
public final class EventsJFR {
    public static final boolean JFR_AVAILABLE = jfrAvailable();

    private static boolean jfrAvailable() {
        try {
            Class.forName("org.graalvm.nativeimage.VMRuntime");
            return false;
        } catch (ClassNotFoundException _) {
            // Class absent means we are not running on a native image, JFR may be available
        }
        try {
            Class.forName("jdk.jfr.Event");
            return true;
        } catch (Throwable _) {
            return false;
        }
    }

    public static final String SERVER_PING = "synaxis.ServerPing";
    public static final String SERVER_TICK = "synaxis.ServerTickTime"; // tools/loadtest/soak.py reads it as a literal

    public static final String CHUNK_GENERATION = "synaxis.ChunkGeneration";
    public static final String CHUNK_LOADING = "synaxis.ChunkLoading";

    public static final String INSTANCE_JOIN = "synaxis.InstanceJoin";
    public static final String INSTANCE_LEAVE = "synaxis.InstanceLeave";

    public static final String PLAYER_JOIN = "synaxis.PlayerJoin";
    public static final String PLAYER_LEAVE = "synaxis.PlayerLeave";
    public static final String PLAYER_COMMAND = "synaxis.PlayerCommand";
    public static final String PLAYER_CHAT = "synaxis.PlayerChat";

    public static EventMarker newServerPing(String remoteAddress) {
        return JFR_AVAILABLE ? new ServerPing(remoteAddress) : NO_OP;
    }

    public static ServerTickMarker newServerTick() {
        return JFR_AVAILABLE ? new ServerTick() : NO_OP;
    }

    public static EventMarker newChunkGeneration(UUID instance, int chunkX, int chunkZ) {
        return JFR_AVAILABLE ? new ChunkGeneration(instance.toString(), chunkX, chunkZ) : NO_OP;
    }

    public static EventMarker newChunkLoading(UUID instance, Class<?> loader, int chunkX, int chunkZ) {
        return JFR_AVAILABLE ? new ChunkLoading(instance.toString(), loader, chunkX, chunkZ) : NO_OP;
    }

    public static EventMarker newInstanceJoin(UUID entity, UUID instance) {
        return JFR_AVAILABLE ? new InstanceJoin(entity.toString(), instance.toString()) : NO_OP;
    }

    public static EventMarker newInstanceLeave(UUID entity, UUID instance) {
        return JFR_AVAILABLE ? new InstanceLeave(entity.toString(), instance.toString()) : NO_OP;
    }

    public static EventMarker newPlayerJoin(UUID player) {
        return JFR_AVAILABLE ? new PlayerJoin(player.toString()) : NO_OP;
    }

    public static EventMarker newPlayerLeave(UUID player) {
        return JFR_AVAILABLE ? new PlayerLeave(player.toString()) : NO_OP;
    }

    public static EventMarker newPlayerCommand(UUID player, String command) {
        return JFR_AVAILABLE ? new PlayerCommand(player.toString(), command) : NO_OP;
    }

    public static EventMarker newPlayerChat(UUID player, String message) {
        return JFR_AVAILABLE ? new PlayerChat(player.toString(), message) : NO_OP;
    }

    @Name(SERVER_PING)
    @Label("Server Ping")
    @Category({"synaxis", "Server"})
    @Description("A server ping (status query) was received")
    private static final class ServerPing extends JFREventWrapper {
        @Label("Remote Address")
        String remoteAddress;

        private ServerPing(String remoteAddress) {
            this.remoteAddress = remoteAddress;
        }
    }

    @Name(SERVER_TICK)
    @Label("Server Tick")
    @Category({"synaxis", "Server"})
    @Description("Time spent ticking the server once")
    private static final class ServerTick extends JFREventWrapper implements ServerTickMarker {
        @Label("Acquisition Time")
        @Description("How long the tick waited to acquire, docs/DESIGN-PROD.md API-PROD-016")
        @Timespan(Timespan.NANOSECONDS)
        long acquisitionTime;

        @Override
        public void acquisitionTime(long nanos) {
            this.acquisitionTime = nanos;
        }
    }

    @Name(CHUNK_GENERATION)
    @Label("Chunk Generation")
    @Category({"synaxis", "World"})
    @Description("Chunk generation from instances' Generator")
    private static final class ChunkGeneration extends JFREventWrapper {
        @Label("Instance UUID")
        String instance;
        @Label("Chunk X")
        int chunkX;
        @Label("Chunk Z")
        int chunkZ;

        private ChunkGeneration(String instance, int chunkX, int chunkZ) {
            this.instance = instance;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    @Name(CHUNK_LOADING)
    @Label("Chunk Loading")
    @Category({"synaxis", "World"})
    @Description("Chunk loading from the instances' ChunkLoader")
    private static final class ChunkLoading extends JFREventWrapper {
        @Label("Instance UUID")
        String instance;
        @Label("Loader Class")
        Class<?> loader;
        @Label("Chunk X")
        int chunkX;
        @Label("Chunk Z")
        int chunkZ;

        private ChunkLoading(String instance, Class<?> loader, int chunkX, int chunkZ) {
            this.instance = instance;
            this.loader = loader;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    @Name(INSTANCE_JOIN)
    @Label("Instance Join")
    @Category({"synaxis", "Instance"})
    @Description("An Entity has joined an instance")
    private static final class InstanceJoin extends JFREventWrapper {
        @Label("Entity UUID")
        String entity;
        @Label("Instance UUID")
        String instance;

        private InstanceJoin(String entity, String instance) {
            this.entity = entity;
            this.instance = instance;
        }
    }

    @Name(INSTANCE_LEAVE)
    @Label("Instance Leave")
    @Category({"synaxis", "Instance"})
    @Description("An Entity has left an instance")
    private static final class InstanceLeave extends JFREventWrapper {
        @Label("Entity UUID")
        String entity;
        @Label("Instance UUID")
        String instance;

        private InstanceLeave(String entity, String instance) {
            this.entity = entity;
            this.instance = instance;
        }
    }

    @Name(PLAYER_JOIN)
    @Label("Player Join")
    @Category({"synaxis", "Player"})
    @Description("A player joined the server")
    private static final class PlayerJoin extends JFREventWrapper {
        @Label("Player UUID")
        String player;

        private PlayerJoin(String player) {
            this.player = player;
        }
    }

    @Name(PLAYER_LEAVE)
    @Label("Player Leave")
    @Category({"synaxis", "Player"})
    @Description("A player left the server")
    private static final class PlayerLeave extends JFREventWrapper {
        @Label("Player UUID")
        String player;

        private PlayerLeave(String player) {
            this.player = player;
        }
    }

    @Name(PLAYER_COMMAND)
    @Label("Player Command")
    @Category({"synaxis", "Player"})
    @Description("A player executed a command")
    private static final class PlayerCommand extends JFREventWrapper {
        @Label("Player UUID")
        String player;
        @Label("Command")
        String command;

        private PlayerCommand(String player, String command) {
            this.player = player;
            this.command = command;
        }
    }

    @Name(PLAYER_CHAT)
    @Label("Player Chat")
    @Category({"synaxis", "Player"})
    @Description("A player sent a chat message")
    private static final class PlayerChat extends JFREventWrapper {
        @Label("Player UUID")
        String player;
        @Label("Message")
        String message;

        private PlayerChat(String player, String message) {
            this.player = player;
            this.message = message;
        }
    }

    public interface EventMarker {
        default void begin() {
        }

        default void end() {
        }

        default void commit() {
        }
    }

    /**
     * The event of one server tick, which also carries the time the tick waited to acquire.
     */
    public interface ServerTickMarker extends EventMarker {
        /**
         * Sets the acquisition wait of the tick.
         *
         * @param nanos {@code TickMonitor#getAcquisitionTime}, in nanoseconds
         */
        default void acquisitionTime(long nanos) {
        }
    }

    private static class JFREventWrapper extends Event implements EventMarker {
    }

    private static final NoOpEvent NO_OP = new NoOpEvent();

    private static class NoOpEvent implements ServerTickMarker {
        @Override
        public void commit() {
            // do nothing
        }
    }
}
