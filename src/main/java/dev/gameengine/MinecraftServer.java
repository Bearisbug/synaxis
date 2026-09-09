package dev.gameengine;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import dev.gameengine.adventure.ClickCallbackManager;
import dev.gameengine.adventure.bossbar.BossBarManager;
import dev.gameengine.codec.Codec;
import dev.gameengine.command.CommandManager;
import dev.gameengine.entity.damage.DamageType;
import dev.gameengine.event.GlobalEventHandler;
import dev.gameengine.exception.ExceptionManager;
import dev.gameengine.instance.InstanceManager;
import dev.gameengine.instance.block.BlockManager;
import dev.gameengine.instance.block.predicate.DataComponentPredicate;
import dev.gameengine.listener.manager.PacketListenerManager;
import dev.gameengine.message.ChatType;
import dev.gameengine.network.ConnectionManager;
import dev.gameengine.network.packet.PacketParser;
import dev.gameengine.network.packet.server.common.PluginMessagePacket;
import dev.gameengine.network.packet.server.play.ServerDifficultyPacket;
import dev.gameengine.network.socket.Server;
import dev.gameengine.registry.DynamicRegistry;
import dev.gameengine.registry.Registries;
import dev.gameengine.registry.Registry;
import dev.gameengine.registry.RegistryKey;
import dev.gameengine.thread.TickSchedulerThread;
import dev.gameengine.timer.SchedulerManager;
import dev.gameengine.utils.PacketSendingUtils;
import dev.gameengine.utils.validate.Check;
import dev.gameengine.world.Difficulty;
import dev.gameengine.world.DimensionType;
import dev.gameengine.world.biome.Biome;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.UnknownNullability;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;

/**
 * The main server class used to start the server and retrieve all the managers.
 * <p>
 * The server needs to be initialized with {@link #init()} and started with {@link #start(String, int)}.
 * You should register all of your dimensions, biomes, commands, events, etc... in-between.
 */
public final class MinecraftServer implements MinecraftConstants {

    public static final ComponentLogger LOGGER = ComponentLogger.logger(MinecraftServer.class);

    // Threads
    public static final String THREAD_NAME_BENCHMARK = "Ms-Benchmark";

    public static final String THREAD_NAME_TICK_SCHEDULER = "Ms-TickScheduler";
    public static final String THREAD_NAME_TICK = "Ms-Tick";

    // Config
    // Can be modified at performance cost when increased
    @Deprecated
    public static final int TICK_PER_SECOND = ServerFlag.SERVER_TICKS_PER_SECOND;
    public static final int TICK_MS = 1000 / TICK_PER_SECOND;

    // In-Game Manager
    private static volatile @UnknownNullability ServerProcess serverProcess;

    private static int compressionThreshold = 256;
    private static String brandName = "game-engine";
    private static Difficulty difficulty = Difficulty.NORMAL;

    public static MinecraftServer init(Auth auth) {
        updateProcess(auth);
        return new MinecraftServer();
    }

    public static MinecraftServer init() {
        return init(new Auth.Offline());
    }

    @ApiStatus.Internal
    public static ServerProcess updateProcess(Auth auth) {
        ServerProcess process = new ServerProcessImpl(auth);
        serverProcess = process;
        return process;
    }

    @ApiStatus.Internal
    public static ServerProcess updateProcess() {
        return updateProcess(new Auth.Offline());
    }

    /**
     * Gets the current server brand name.
     *
     * @return the server brand name
     */
    public static String getBrandName() {
        return brandName;
    }

    /**
     * Changes the server brand name and send the change to all connected players.
     *
     * @param brandName the server brand name
     * @throws NullPointerException if {@code brandName} is null
     */
    public static void setBrandName(String brandName) {
        MinecraftServer.brandName = brandName;
        PacketSendingUtils.broadcastPlayPacket(PluginMessagePacket.brandPacket(brandName));
    }

    /**
     * Gets the server difficulty showed in game option.
     *
     * @return the server difficulty
     */
    public static Difficulty getDifficulty() {
        return difficulty;
    }

    /**
     * Changes the server difficulty and send the appropriate packet to all connected clients.
     *
     * @param difficulty the new server difficulty
     */
    public static void setDifficulty(Difficulty difficulty) {
        MinecraftServer.difficulty = difficulty;
        PacketSendingUtils.broadcastPlayPacket(new ServerDifficultyPacket(difficulty, true));
    }

    public static @UnknownNullability ServerProcess process() {
        return serverProcess;
    }

    /**
     * Gets the registries owned by the current server process.
     *
     * @return the current server registries
     * @throws NullPointerException if the server has not been initialized
     */
    public static Registries getRegistries() {
        return Objects.requireNonNull(serverProcess, "serverProcess").registries();
    }

    public static GlobalEventHandler getGlobalEventHandler() {
        return serverProcess.eventHandler();
    }

    public static PacketListenerManager getPacketListenerManager() {
        return serverProcess.packetListener();
    }

    public static InstanceManager getInstanceManager() {
        return serverProcess.instance();
    }

    public static BlockManager getBlockManager() {
        return serverProcess.block();
    }

    public static CommandManager getCommandManager() {
        return serverProcess.command();
    }

    public static SchedulerManager getSchedulerManager() {
        return serverProcess.scheduler();
    }

    public static ExceptionManager getExceptionManager() {
        return serverProcess.exception();
    }

    public static ConnectionManager getConnectionManager() {
        return serverProcess.connection();
    }

    public static BossBarManager getBossBarManager() {
        return serverProcess.bossBar();
    }

    public static PacketParser.Client getPacketParser() {
        return serverProcess.packetParser();
    }

    public static boolean isStarted() {
        return serverProcess.isAlive();
    }

    public static boolean isStopping() {
        return !isStarted();
    }

    /**
     * Gets the chunk view distance of the server.
     * <p>
     * Deprecated in favor of {@link ServerFlag#CHUNK_VIEW_DISTANCE}
     *
     * @return the chunk view distance
     */
    @Deprecated
    public static int getChunkViewDistance() {
        return ServerFlag.CHUNK_VIEW_DISTANCE;
    }

    /**
     * Gets the entity view distance of the server.
     * <p>
     * Deprecated in favor of {@link ServerFlag#ENTITY_VIEW_DISTANCE}
     *
     * @return the entity view distance
     */
    @Deprecated
    public static int getEntityViewDistance() {
        return ServerFlag.ENTITY_VIEW_DISTANCE;
    }

    /**
     * Gets the compression threshold of the server.
     *
     * @return the compression threshold, 0 means that compression is disabled
     */
    public static int getCompressionThreshold() {
        return compressionThreshold;
    }

    /**
     * Changes the compression threshold of the server.
     * <p>
     * WARNING: this need to be called before {@link #start(SocketAddress)}.
     *
     * @param compressionThreshold the new compression threshold, 0 to disable compression
     * @throws IllegalStateException if this is called after the server started
     */
    public static void setCompressionThreshold(int compressionThreshold) {
        Check.stateCondition(serverProcess != null && serverProcess.isAlive(), "The compression threshold cannot be changed after the server has been started.");
        MinecraftServer.compressionThreshold = compressionThreshold;
    }

    public static ClickCallbackManager getClickCallbackManager() {
        return serverProcess.clickCallbackManager();
    }

    public static DynamicRegistry<ChatType> getChatTypeRegistry() {
        return serverProcess.chatType();
    }

    public static DynamicRegistry<DimensionType> getDimensionTypeRegistry() {
        return serverProcess.dimensionType();
    }

    public static DynamicRegistry<Biome> getBiomeRegistry() {
        return serverProcess.biome();
    }

    public static DynamicRegistry<DamageType> getDamageTypeRegistry() {
        return serverProcess.damageType();
    }

    public static DynamicRegistry<BinaryTag> getOpaqueRegistry(RegistryKey<Registry<BinaryTag>> key) {
        return serverProcess.opaque(key);
    }

    public static DynamicRegistry<Codec<? extends DataComponentPredicate>> componentPredicateTypes() {
        return serverProcess.componentPredicateTypes();
    }

    public static Server getServer() {
        return serverProcess.server();
    }

    /**
     * Starts the server.
     * <p>
     * It should be called after {@link #init()} and probably your own initialization code.
     *
     * @param address the server address
     * @throws IllegalStateException if called before {@link #init()} or if the server is already running
     */
    public void start(SocketAddress address) {
        serverProcess.start(address);
        serverProcess.dispatcher().start();
        new TickSchedulerThread(serverProcess).start();
    }

    public void start(String address, int port) {
        start(new InetSocketAddress(address, port));
    }

    /**
     * Stops this server properly (saves if needed, kicking players, etc.)
     */
    public static void stopCleanly() {
        serverProcess.stop();
    }
}
